"""검증한 모델별 변환 경로로 학습 어댑터를 새 GGUF 모델에 반영한다."""

import argparse
import hashlib
import importlib.metadata
import json
import math
import os
import re
import shutil
import struct
import subprocess
import sys
from pathlib import Path

from model_export_profiles import GENERIC_EXPORTER, export_capability
from model_export_tensors import (ExportError, dense_model_shapes, export_hf, model_shapes, read_json,
                                  tensor_header, verify_fused_dense)
from training_profiles import ProfileError, lora_parameters, validate_profile

LLAMA_CPP_REVISION = "620d2225cc436ab0b001e3200e590e3b286ef9ab"
MLX_LM_VERSION = "0.32.0"
BASE_MODEL_ID = "mlx-community/Qwen3.8-27B-4bit"
BASE_MODEL_REVISION = "10c35caafbb80f7dc6a7a432cdd11af10a6d4818"
CONVERTER_CHECK = (
    "import sys, importlib.metadata as m; "
    "assert sys.version_info >= (3, 10); "
    "assert m.version('torch').split('+')[0] == '2.11.0'; "
    "assert m.version('transformers') == '4.57.6'; "
    "v = tuple(map(int, m.version('numpy').split('.'))); assert (2, 2, 6) <= v < (2, 3, 0); "
    "import torch, numpy, transformers, safetensors, sentencepiece"
)


def checksum(path):
    """큰 모델 파일을 작은 블록으로 읽어 해시를 계산한다."""
    value = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def write_json(path, value):
    """진행 상태는 같은 폴더에서 원자적으로 교체한다."""
    path = Path(path)
    temporary = path.with_name(path.name + ".tmp")
    descriptor = os.open(temporary, os.O_CREAT | os.O_TRUNC | os.O_WRONLY, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as target:
        json.dump(value, target, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
        target.write("\n")
        target.flush()
        os.fsync(target.fileno())
    os.replace(temporary, path)


def run(command, *, capture=False, cwd=None):
    """고정한 도구만 셸 없이 실행하며 도구 출력에 원문이나 경로가 남지 않게 한다."""
    inherited = os.environ.get("DOENAAI_MODEL_BUILD_LOCK_FDS", "")
    descriptors = ()
    if inherited:
        if not re.fullmatch(r"[0-9]+,[0-9]+", inherited):
            raise ExportError("모델 빌드 잠금 전달 형식을 확인하세요.")
        descriptors = tuple(int(value) for value in inherited.split(","))
        if len(set(descriptors)) != 2 or any(value < 3 for value in descriptors):
            raise ExportError("모델 빌드 잠금 파일을 확인하세요.")
        for descriptor in descriptors:
            os.fstat(descriptor)
    result = subprocess.run(command, cwd=cwd, stdin=subprocess.DEVNULL,
                            stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL, check=False, pass_fds=descriptors)
    if result.returncode != 0:
        raise ExportError("고정한 모델 변환 도구 실행에 실패했습니다.")
    return result.stdout.decode("utf-8").strip() if capture else None


def toolchain(llama_cpp, converter_python, quantizer_sha256):
    """고정된 소스 체크아웃과 관리자가 지정한 양자화 실행 파일 해시를 확인한다."""
    root, python = Path(llama_cpp), Path(converter_python)
    quantizer, converter = root / "build/bin/llama-quantize", root / "convert_hf_to_gguf.py"
    if (not root.is_absolute() or root.is_symlink() or not root.is_dir()
            or not python.is_absolute() or not python.is_file() or not os.access(python, os.X_OK)
            or quantizer.is_symlink() or not quantizer.is_file() or not os.access(quantizer, os.X_OK)
            or converter.is_symlink() or not converter.is_file()
            or not isinstance(quantizer_sha256, str) or not re.fullmatch(r"[0-9a-f]{64}", quantizer_sha256)):
        raise ExportError("변환 도구의 절대 경로와 양자화 실행 파일 해시를 설정하세요.")
    if run(["git", "-C", str(root), "rev-parse", "HEAD"], capture=True) != LLAMA_CPP_REVISION:
        raise ExportError("llama.cpp를 지정한 고정 버전으로 준비하세요.")
    if run(["git", "-C", str(root), "status", "--porcelain", "--untracked-files=no"], capture=True):
        raise ExportError("변환 도구의 추적 파일이 고정 버전과 다릅니다.")
    if checksum(quantizer) != quantizer_sha256:
        raise ExportError("양자화 실행 파일 해시가 설정과 다릅니다.")
    run([str(python), "-c", CONVERTER_CHECK])
    return python, converter, quantizer


def _read(source, size):
    value = source.read(size)
    if len(value) != size or source.tell() > 128 * 1024**2:
        raise ExportError("GGUF 메타데이터가 불완전하거나 제한을 초과했습니다.")
    return value


def _number(source, format):
    return struct.unpack("<" + format, _read(source, struct.calcsize("<" + format)))[0]


def _string(source, *, keep=True):
    size = _number(source, "Q")
    if size > 64 * 1024**2:
        raise ExportError("GGUF 문자열 크기를 확인하세요.")
    value = _read(source, size)
    if keep:
        try:
            return value.decode("utf-8")
        except UnicodeError:
            raise ExportError("GGUF 문자열 형식을 확인하세요.") from None
    return None


def _value(source, kind, *, keep=False):
    formats = {0: "B", 1: "b", 2: "H", 3: "h", 4: "I", 5: "i", 6: "f", 7: "?", 10: "Q", 11: "q", 12: "d"}
    if kind in formats:
        return _number(source, formats[kind])
    if kind == 8:
        return _string(source, keep=keep)
    if kind == 9:
        element, count = _number(source, "I"), _number(source, "Q")
        if element == 9 or count > 2**21:
            raise ExportError("GGUF 배열 형식을 확인하세요.")
        for _ in range(count):
            _value(source, element)
        return None
    raise ExportError("GGUF에 지원하지 않는 메타데이터 형식이 있습니다.")


def gguf_identity(path):
    """서명된 새 형식도 실제 GGUF의 모델 구조와 양자화 식별자를 다시 확인한다."""
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise ExportError("GGUF 모델 파일을 확인하세요.")
    with path.open("rb") as source:
        if _read(source, 4) != b"GGUF" or _number(source, "I") != 3:
            raise ExportError("완성 모델은 GGUF 3 형식이어야 합니다.")
        tensors, count = _number(source, "Q"), _number(source, "Q")
        if not 1 <= tensors <= 100000 or not 1 <= count <= 65536:
            raise ExportError("완성 모델의 GGUF 메타데이터 개수를 확인하세요.")
        result, seen = {}, set()
        for _ in range(count):
            key, kind = _string(source), _number(source, "I")
            if key in seen:
                raise ExportError("GGUF 메타데이터에 중복 필드가 있습니다.")
            seen.add(key)
            keep = key in {"general.architecture", "general.file_type", "general.type"}
            value = _value(source, kind, keep=keep)
            if keep:
                result[key] = value
    return result


def verify_gguf(path, config, *, quantized=True, profile=None):
    """완성 파일의 구조·양자화·필수 모델 차원을 확인하고 어댑터 GGUF는 거부한다."""
    path = Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size < 32:
        raise ExportError("완성된 GGUF 모델 파일이 없습니다.")
    if profile is None:
        text, expected = model_shapes(config)
        architecture, optional = "qwen35", {}
    else:
        _, expected, optional = dense_model_shapes(config, profile)
        text, architecture = config, profile["exportArchitecture"]
    wanted = {"general.architecture", "general.file_type", "general.type", "general.alignment",
              architecture + ".block_count", architecture + ".embedding_length", "tokenizer.chat_template"}
    metadata, ranges, names, tensor_types = {}, [], set(), set()
    with path.open("rb") as source:
        if _read(source, 4) != b"GGUF" or _number(source, "I") != 3:
            raise ExportError("완성 모델은 GGUF 3 형식이어야 합니다.")
        tensor_count, count = _number(source, "Q"), _number(source, "Q")
        if not len(expected) <= tensor_count <= len(expected) + len(optional) or not 1 <= count <= 65536:
            raise ExportError("GGUF 모델의 전체 텐서 수가 기반 모델과 다릅니다.")
        seen_metadata = set()
        for _ in range(count):
            key, kind = _string(source), _number(source, "I")
            if key in seen_metadata:
                raise ExportError("GGUF 메타데이터에 중복 필드가 있습니다.")
            seen_metadata.add(key)
            value = _value(source, kind, keep=key in wanted)
            if key in wanted:
                metadata[key] = value
        for _ in range(tensor_count):
            name, dims = _string(source), _number(source, "I")
            if name in names or not 1 <= dims <= 4:
                raise ExportError("GGUF 텐서 명세를 확인하세요.")
            names.add(name)
            shape = [_number(source, "Q") for _ in range(dims)]
            if any(not 1 <= dimension <= 2**31 for dimension in shape):
                raise ExportError("GGUF 텐서 차원을 확인하세요.")
            if profile is not None and tuple(shape) != (expected | optional).get(name):
                raise ExportError("GGUF 텐서 이름과 차원이 선택한 모델 구조와 다릅니다.")
            tensor_type, offset = _number(source, "I"), _number(source, "Q")
            # Q4_K_M이 일부 텐서를 높은 정밀도로 보존하거나 작은 블록으로 대체하는 형식도 검사한다.
            blocks = {0: (1, 4), 1: (1, 2), 6: (32, 22), 7: (32, 24), 8: (32, 34),
                      12: (256, 144), 13: (256, 176), 14: (256, 210), 30: (1, 2)}
            if tensor_type not in blocks or shape[0] % blocks[tensor_type][0]:
                raise ExportError("GGUF 텐서의 양자화 블록을 확인하세요.")
            block, size = blocks[tensor_type]
            tensor_types.add(tensor_type)
            ranges.append((offset, offset + math.prod(shape) // block * size))
        alignment = metadata.get("general.alignment", 32)
        if type(alignment) is not int or alignment not in (16, 32, 64):
            raise ExportError("GGUF 데이터 정렬을 확인하세요.")
        data_start = (source.tell() + alignment - 1) // alignment * alignment
    allowed_types = {0, 1, 6, 7, 8, 12, 13, 14, 30} if quantized else {0, 1, 30}
    required_type, file_type = (12, 15) if quantized else (1, 1)
    if (metadata.get("general.architecture") != architecture or metadata.get("general.file_type") != file_type
            or metadata.get("general.type", "model") != "model"
            or metadata.get(architecture + ".block_count") != text["num_hidden_layers"]
            or metadata.get(architecture + ".embedding_length") != text["hidden_size"]
            or not isinstance(metadata.get("tokenizer.chat_template"), str) or not metadata["tokenizer.chat_template"]
            or required_type not in tensor_types or not tensor_types <= allowed_types
            or (profile is not None and not set(expected) <= names)
            or any(start % alignment or not 0 <= start < end <= path.stat().st_size - data_start for start, end in ranges)):
        raise ExportError("GGUF 모델 구조·양자화·대화 형식이 기대한 결과와 다릅니다.")
    end = 0
    for start, stop in sorted(ranges):
        if start < end:
            raise ExportError("GGUF 모델의 텐서 데이터가 겹칩니다.")
        end = stop


def protected_files(path):
    """기반 모델을 수정하지 않았는지 파일 크기와 수정 시각으로 대조한다."""
    return {file.name: (file.stat().st_size, file.stat().st_mtime_ns)
            for file in Path(path).iterdir() if file.is_file()}


def export_binding(args, manifest, config, adapter):
    """서버가 고정한 모델 식별자와 프로필을 다시 확인하고 기존 경로를 별도로 유지한다."""
    path = getattr(args, "binding_file", None)
    if path is None:
        if manifest.get("modelId") != BASE_MODEL_ID or manifest.get("revision") != BASE_MODEL_REVISION:
            raise ExportError("현재 검증한 기반 모델의 정확한 고정 버전이 필요합니다.")
        return None
    binding = read_json(path)
    if set(binding) != {"baseModel", "baseRevision", "profile"}:
        raise ExportError("모델 변환의 고정 식별 정보 형식을 확인하세요.")
    profile = validate_profile(binding["profile"])
    capability = export_capability(profile, base_model=binding["baseModel"], revision=binding["baseRevision"])
    if (not capability["supported"] or profile.get("exporter") != GENERIC_EXPORTER
            or manifest.get("modelId") != binding["baseModel"] or manifest.get("revision") != binding["baseRevision"]):
        raise ExportError("선택한 모델과 고정한 변환 프로필 또는 다운로드 버전이 다릅니다.")
    shapes, _, _ = dense_model_shapes(config, profile)
    adapter_config = read_json(adapter / "adapter_config.json")
    if (adapter_config.get("fine_tune_type", "lora") != "lora"
            or adapter_config.get("num_layers") != profile["loraLayers"]
            or adapter_config.get("lora_parameters") != lora_parameters(profile)):
        raise ExportError("원본 어댑터의 학습 설정이 고정한 모델 프로필과 다릅니다.")
    expected = {}
    for layer in range(config["num_hidden_layers"] - profile["loraLayers"], config["num_hidden_layers"]):
        for key in profile["loraKeys"]:
            prefix = f"model.layers.{layer}.{key}"
            shape = shapes.get(prefix + ".weight")
            if shape is None or len(shape) != 2:
                raise ExportError("공통 변환기는 확인한 계층 안의 선형 LoRA 가중치만 지원합니다.")
            expected[prefix + ".lora_a"] = (shape[1], profile["loraRank"])
            expected[prefix + ".lora_b"] = (profile["loraRank"], shape[0])
    actual = tensor_header(adapter / "adapters.safetensors")
    if (set(actual) != set(expected) or any(tuple(actual[key]["shape"]) != shape
            or actual[key]["dtype"] not in ("F16", "BF16", "F32") for key, shape in expected.items())):
        raise ExportError("원본 어댑터의 LoRA 텐서와 차원이 모델별 학습 설정과 다릅니다.")
    return profile


def execute(args):
    """모델별 병합 경로와 공식 고정 도구를 사용하며 검증한 완성 파일만 보고한다."""
    os.umask(0o077)
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    os.environ["TOKENIZERS_PARALLELISM"] = "false"
    output, model, adapter = Path(args.output), Path(args.model), Path(args.adapter)
    if (not output.is_absolute() or output.is_symlink() or not output.is_dir() or list(output.iterdir())
            or not model.is_absolute() or not adapter.is_absolute() or adapter.is_symlink()):
        raise ExportError("새 모델 출력 폴더는 비어 있는 별도 절대 경로여야 합니다.")
    if importlib.metadata.version("mlx-lm") != MLX_LM_VERSION:
        raise ExportError("학습 때 사용한 고정 MLX-LM 버전이 필요합니다.")
    if shutil.disk_usage(output).free < 180 * 1024**3:
        raise ExportError("전체 모델 변환에는 출력 디스크의 빈 공간이 180 GiB 이상 필요합니다.")
    manifest = read_json(model / "download_manifest.json")
    config = read_json(model / "config.json")
    profile = export_binding(args, manifest, config, adapter)
    python, converter, quantizer = toolchain(args.llama_cpp, args.converter_python, args.quantizer_sha256)
    before = protected_files(model)
    adapter_hash, adapter_config_hash = checksum(adapter / "adapters.safetensors"), checksum(adapter / "adapter_config.json")
    progress_path = Path(args.progress)
    def progress(status, value):
        write_json(progress_path, {"status": status, "progress": value})
    progress("FUSING", 1)
    temporary = output / ".tmp"
    temporary.mkdir(mode=0o700)
    os.environ["TMPDIR"] = str(temporary)
    canonical = output / "hf-model"
    if profile is None:
        export_hf(model, adapter, canonical, progress)
    else:
        # 부모 결과를 이어 학습한 어댑터에는 누적 값이 들어 있으므로 현재 어댑터를 한 번만 반영한다.
        run([sys.executable, "-m", "mlx_lm", "fuse", "--model", str(model), "--adapter-path", str(adapter),
             "--save-path", str(canonical), "--dequantize"], cwd=output)
        verify_fused_dense(canonical, config, profile)
    progress("CONVERTING", 55)
    f16, artifact = output / "model-f16.gguf", output / "model.gguf"
    run([str(python), str(converter), str(canonical), "--outfile", str(f16), "--outtype", "f16",
         "--use-temp-file", "--no-mtp"], cwd=output)
    verify_gguf(f16, config, quantized=False, profile=profile)
    shutil.rmtree(canonical)
    progress("QUANTIZING", 75)
    run([str(quantizer), str(f16), str(artifact), "Q4_K_M"], cwd=output)
    verify_gguf(artifact, config, profile=profile)
    if (before != protected_files(model) or checksum(adapter / "adapters.safetensors") != adapter_hash
            or checksum(adapter / "adapter_config.json") != adapter_config_hash):
        raise ExportError("변환 중 기반 모델이나 원본 어댑터가 변경됐습니다.")
    # 중간 전체 가중치는 게시하지 않으며 완성 GGUF 검증 후에만 정리한다.
    f16.unlink()
    shutil.rmtree(temporary)
    report = {"status": "complete", "artifactSha256": checksum(artifact), "artifactSize": artifact.stat().st_size,
              "quantization": "Q4_K_M", "architecture": profile["exportArchitecture"] if profile else "qwen35",
              "toolchain": {"mlxLmVersion": MLX_LM_VERSION, "llamaCppRevision": LLAMA_CPP_REVISION}}
    if profile is not None:
        report.update(exporter=GENERIC_EXPORTER, modelType=profile["modelType"], profile=profile)
    write_json(output / "export_report.json", report)
    progress("QUANTIZING", 94)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("model", "adapter", "output", "llama-cpp", "converter-python", "progress", "quantizer-sha256"):
        parser.add_argument("--" + flag, required=True)
    parser.add_argument("--binding-file")
    try:
        execute(parser.parse_args())
    except (ExportError, ProfileError, OSError, ValueError, RuntimeError, ImportError, importlib.metadata.PackageNotFoundError):
        print("모델 병합·변환에 실패했습니다. 기반 모델, 고정 도구, 디스크 공간과 실행 환경을 확인하세요.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
