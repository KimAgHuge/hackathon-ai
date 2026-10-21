"""등록된 모델별 설정으로 공통 MLX LoRA 시험 학습과 새 프로세스 재로딩을 실행한다."""

import argparse
import hashlib
import importlib.metadata
import json
import math
import os
import time
from pathlib import Path

from dataset_contract import DatasetError, digest, prompt_contract, read_jsonl, strict_json
from training_profiles import lora_parameters, validate_profile
from trial_dataset import tokenize_messages

ROOT = Path(__file__).resolve().parent
PURPOSE = "approved_case_smoke_test_do_not_deploy"


def file_hash(path):
    """원문을 출력하지 않고 파일의 무결성만 계산한다."""
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise DatasetError("보존 검사에 사용할 일반 파일을 확인하세요.")
    checksum = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            checksum.update(chunk)
    return checksum.hexdigest()


def read_config(path):
    """작고 엄격한 JSON 설정만 허용하며 중복 필드와 비정상 숫자를 거부한다."""
    path = Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 1024 * 1024:
        raise DatasetError("학습 설정 파일을 확인하지 못했습니다.")
    value = strict_json(path.read_text())
    if not isinstance(value, dict):
        raise DatasetError("학습 설정은 JSON 객체여야 합니다.")
    return value


def adapter_settings(profile):
    """새 어댑터와 부모 어댑터에 동일하게 적용할 설정을 만든다."""
    return {"fine_tune_type": "lora", "num_layers": profile["loraLayers"],
            "lora_parameters": lora_parameters(profile), "purpose": PURPOSE}


def validate_parent(adapter, profile):
    """같은 학습 구조의 부모 어댑터만 허용하고 필수 두 파일의 해시를 고정한다."""
    if adapter is None:
        return None
    adapter = Path(adapter)
    if adapter.is_symlink() or not adapter.is_dir():
        raise DatasetError("이어 학습할 부모 어댑터 폴더를 확인하세요.")
    config = read_config(adapter / "adapter_config.json")
    expected = adapter_settings(profile)
    if any(config.get(key) != expected[key] for key in ("fine_tune_type", "num_layers", "lora_parameters")):
        raise DatasetError("부모 어댑터의 학습 대상과 설정이 현재 프로필과 다릅니다.")
    return {name: file_hash(adapter / name) for name in ("adapters.safetensors", "adapter_config.json")}


def validate_module_targets(model, profile, supported_types):
    """선택한 모든 층에서 실제 대상 모듈과 LoRA 지원 자료형을 확인한다."""
    layers = getattr(model, "layers", None)
    if not isinstance(layers, (list, tuple)) or len(layers) < profile["loraLayers"]:
        raise DatasetError("모델의 학습 층 목록과 요청한 층 수를 확인하세요.")
    resolved = []
    for index in range(len(layers) - profile["loraLayers"], len(layers)):
        modules = dict(layers[index].named_modules())
        for key in profile["loraKeys"]:
            module = modules.get(key)
            if module is None or not (isinstance(module, supported_types) or callable(getattr(module, "to_lora", None))):
                raise DatasetError("실제 모델에 LoRA 대상 모듈이 없거나 지원하지 않는 자료형입니다.")
            resolved.append({"layer": index, "module": key})
    return resolved


def protected_files(model, excluded_adapter=None):
    """기반 파일 상태와 기존 모든 어댑터 파일의 해시를 기록한다."""
    excluded = excluded_adapter.resolve() if excluded_adapter is not None else None
    existing = {}
    for path in (ROOT / "adapters").rglob("*"):
        if path.is_file() and (excluded is None or excluded not in path.resolve().parents):
            existing[str(path.relative_to(ROOT / "adapters"))] = file_hash(path)
    return {"model": {path.name: [path.stat().st_size, path.stat().st_mtime_ns]
                      for path in Path(model).iterdir() if path.is_file()}, "existingAdapter": existing}


def main():
    """실행 경로와 프로필을 확인한 뒤에만 GPU 라이브러리를 불러온다."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("train", "reload"))
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--adapter", type=Path, required=True)
    parser.add_argument("--iters", type=int, default=10)
    parser.add_argument("--profile-file", type=Path, required=True)
    parser.add_argument("--resume-adapter", type=Path)
    args = parser.parse_args()
    if not 1 <= args.iters <= 20:
        raise DatasetError("시험 학습은 1~20회 업데이트만 허용합니다.")
    if (args.adapter.resolve().parent != (ROOT / "adapters").resolve()
            or not args.adapter.name.startswith("trial-") or args.adapter.is_symlink()):
        raise DatasetError("어댑터는 별도의 training/adapters/trial- 폴더를 사용하세요.")
    profile = validate_profile(read_config(args.profile_file))
    if read_config(args.model / "config.json").get("model_type") != profile["modelType"]:
        raise DatasetError("프로필과 실제 모델의 구조가 다릅니다.")
    manifest = read_config(args.dataset / "manifest.json")
    if prompt_contract()[1] != manifest.get("contractSha256"):
        raise DatasetError("자료를 준비한 뒤 프롬프트·입력 계약이 변경됐습니다.")
    if manifest.get("model") != read_config(args.model / "download_manifest.json"):
        raise DatasetError("학습 자료와 실제 기반 모델의 고정 버전이 다릅니다.")
    if type(manifest.get("maxTokens")) is not int or manifest["maxTokens"] > profile["maxSequenceLength"]:
        raise DatasetError("자료의 길이 상한이 등록된 학습 프로필보다 큽니다.")
    report_path = args.dataset / ("training-report.json" if args.phase == "train" else "reload-report.json")
    if report_path.exists() or report_path.is_symlink():
        raise DatasetError("기존 학습 보고서는 덮어쓰지 않습니다.")
    parent_hashes = validate_parent(args.resume_adapter, profile)
    os.environ.setdefault("HF_HUB_OFFLINE", "1")
    os.environ.setdefault("HF_HOME", str(ROOT / ".cache/huggingface"))
    os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
    import mlx.core as mx
    import mlx.nn as nn
    import mlx.optimizers as optim
    from mlx.utils import tree_flatten
    from mlx_lm import load
    from mlx_lm.models.switch_layers import QuantizedSwitchLinear, SwitchLinear
    from mlx_lm.tuner.trainer import default_loss, grad_checkpoint
    from mlx_lm.tuner.utils import linear_to_lora_layers, load_adapters

    mx.set_memory_limit(48 * 2**30)
    mx.set_cache_limit(1024**3)
    mx.random.seed(0)
    started = time.perf_counter()
    before_files = protected_files(args.model, args.adapter)
    print(json.dumps({"phase": "loading", "action": args.phase}), flush=True)
    model, tokenizer = load(str(args.model))
    resolved = validate_module_targets(model, profile, (nn.Linear, nn.QuantizedLinear, nn.Embedding,
                                                       nn.QuantizedEmbedding, SwitchLinear, QuantizedSwitchLinear))
    samples = {}
    for split in ("train", "valid", "test"):
        path = args.dataset / f"{split}.jsonl"
        rows = read_jsonl(path) if path.stat().st_size else []
        samples[split] = []
        for row in rows:
            tokens, offset = tokenize_messages(tokenizer, row["messages"])
            if (tokens != row["tokens"] or offset != row["offset"]
                    or len(tokens) > profile["maxSequenceLength"] or len(tokens) > manifest["maxTokens"]):
                raise DatasetError("토큰 길이 또는 정답 경계가 준비 당시와 다릅니다.")
            samples[split].append((tokens, offset))
    if len(samples["train"]) < profile["batchSize"]:
        raise DatasetError("한 배치를 만들 수 있는 승인 학습 자료가 필요합니다.")
    probe_tokens = samples["train"][0][0][:16]
    probe = mx.array([probe_tokens])
    if args.phase == "reload":
        reference = read_config(args.dataset / "training-report.json")
        if (reference.get("profileSha256") != digest(profile)
                or file_hash(args.adapter / "adapters.safetensors") != reference.get("adapterSha256")
                or read_config(args.adapter / "adapter_config.json") != adapter_settings(profile)):
            raise DatasetError("학습 후 프로필 또는 어댑터가 변경됐습니다.")
        load_adapters(model, str(args.adapter))
        saved = mx.load(str(args.adapter / "adapters.safetensors"))
        current = dict(tree_flatten(model.parameters()))
        if not saved or not all(key in current and mx.array_equal(current[key], value).item() for key, value in saved.items()):
            raise DatasetError("새 프로세스의 어댑터 텐서가 저장값과 다릅니다.")
        model.eval()
        logits = model(probe)[:, -1, :256].astype(mx.float32)
        difference = mx.max(mx.abs(logits - mx.array(reference["probeLogits"]))).item()
        if not math.isfinite(difference) or difference > 0.02:
            raise DatasetError("새 프로세스의 확인 출력이 저장 당시와 다릅니다.")
        result = {"status": "complete", "adapterTensors": len(saved), "maxLogitDifference": difference,
                  "profileSha256": digest(profile), "seconds": time.perf_counter() - started,
                  "peakGpuGiB": mx.get_peak_memory() / 2**30}
        if protected_files(args.model, args.adapter) != before_files:
            raise DatasetError("재로딩 중 기반 모델 또는 기존 어댑터가 변경됐습니다.")
        report_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps(result, ensure_ascii=False), flush=True)
        return

    args.adapter.mkdir(mode=0o700, exist_ok=False)
    model.freeze()
    if args.resume_adapter is None:
        linear_to_lora_layers(model, profile["loraLayers"], lora_parameters(profile))
    else:
        load_adapters(model, str(args.resume_adapter))
        model.freeze()
        for _, module in model.named_modules():
            keys = [key for key in ("lora_a", "lora_b") if hasattr(module, key)]
            if keys:
                module.unfreeze(recurse=False, keys=keys, strict=True)
    trainable = dict(tree_flatten(model.trainable_parameters()))
    if not trainable or any(not name.endswith(("lora_a", "lora_b")) for name in trainable):
        raise DatasetError("학습 대상이 LoRA 가중치로 제한되지 않았습니다.")
    original = {key: mx.array(value) for key, value in trainable.items()}
    mx.eval(original)
    if profile["gradientCheckpointing"]:
        checked_types = set()
        for layer in model.layers[-profile["loraLayers"]:]:
            if type(layer) not in checked_types:
                grad_checkpoint(layer)
                checked_types.add(type(layer))
    optimizer = optim.Adam(learning_rate=profile["learningRate"])
    loss_grad = nn.value_and_grad(model, default_loss)

    def batch_for(values):
        """정답과 근거를 자르지 않고 뒤쪽 채움 토큰을 손실에서 제외한다."""
        maximum = max(len(tokens) for tokens, _ in values)
        batch = mx.array([tokens + [0] * (maximum - len(tokens)) for tokens, _ in values])
        lengths = mx.array([[offset, len(tokens) - 1] for tokens, offset in values])
        return batch, lengths

    def validation_loss():
        """고정된 검증 그룹만 평가 모드로 측정하고 시험 자료는 실행하지 않는다."""
        model.eval()
        total, count = 0.0, 0
        for sample in samples["valid"]:
            loss, supervised = default_loss(model, *batch_for([sample]))
            mx.eval(loss, supervised)
            value, tokens = loss.item(), supervised.item()
            if not math.isfinite(value) or tokens <= 0:
                raise DatasetError("검증 손실 또는 정답 토큰 수가 유효하지 않습니다.")
            total += value * tokens
            count += tokens
            mx.clear_cache()
        return total / count if count else None

    baseline = validation_loss()
    model.train()
    # 앞부분은 모두 고정돼 있어 추론 커널을 사용하고 실제 학습 층만 학습 모드를 유지한다.
    for layer in model.layers[:-profile["loraLayers"]]:
        layer.eval()
    steps = []
    for step in range(args.iters):
        count = profile["batchSize"]
        values = [samples["train"][(step * count + index) % len(samples["train"])] for index in range(count)]
        (loss, supervised), gradients = loss_grad(model, *batch_for(values))
        norm = mx.sqrt(sum(mx.sum(value.astype(mx.float32) ** 2) for _, value in tree_flatten(gradients)))
        mx.eval(loss, supervised, norm)
        value, magnitude = loss.item(), norm.item()
        if not math.isfinite(value) or not math.isfinite(magnitude) or magnitude <= 0 or supervised.item() <= 0:
            raise DatasetError("학습 손실 또는 기울기가 유효하지 않습니다.")
        optimizer.update(model, gradients)
        mx.eval(model.trainable_parameters(), optimizer.state)
        item = {"step": step + 1, "loss": value, "gradientNorm": magnitude,
                "seconds": time.perf_counter() - started, "peakGpuGiB": mx.get_peak_memory() / 2**30}
        steps.append(item)
        with (args.dataset / "training-steps.jsonl").open("a") as target:
            target.write(json.dumps(item) + "\n")
        print(json.dumps(item), flush=True)
        del loss, supervised, gradients, norm
        mx.clear_cache()
    current = dict(tree_flatten(model.trainable_parameters()))
    delta = max(mx.max(mx.abs(current[key] - original[key])).item() for key in current)
    if not math.isfinite(delta) or delta <= 0:
        raise DatasetError("학습 후 어댑터의 유효한 변화를 확인하지 못했습니다.")
    after = validation_loss()
    mx.save_safetensors(str(args.adapter / "adapters.safetensors"), current)
    (args.adapter / "adapter_config.json").write_text(json.dumps(adapter_settings(profile), ensure_ascii=False, indent=2) + "\n")
    model.eval()
    logits = model(probe)[:, -1, :256].astype(mx.float32)
    mx.eval(logits)
    if protected_files(args.model, args.adapter) != before_files:
        raise DatasetError("기반 모델 또는 기존 어댑터의 보존 검사에 실패했습니다.")
    if parent_hashes != validate_parent(args.resume_adapter, profile):
        raise DatasetError("이어 학습하는 동안 부모 어댑터가 변경됐습니다.")
    result = {"status": "complete", "purpose": PURPOSE, "model": manifest["model"],
              "versions": {name: importlib.metadata.version(name) for name in ("mlx", "mlx-lm", "transformers")},
              "datasetManifestSha256": file_hash(args.dataset / "manifest.json"),
              "contractSha256": manifest["contractSha256"], "profileSha256": digest(profile), "profile": profile,
              "iterations": args.iters, "layers": profile["loraLayers"], "rank": profile["loraRank"],
              "learningRate": profile["learningRate"], "thinking": False, "trainableModules": profile["loraKeys"],
              "resolvedModules": resolved, "truncatedSamples": 0, "steps": steps,
              "validationLossBefore": {"valid": baseline}, "validationLossAfter": {"valid": after},
              "adapterMaxDelta": delta, "adapterTensors": len(current),
              "adapterSha256": file_hash(args.adapter / "adapters.safetensors"),
              "protectedFilesUnchanged": True, "protectedFiles": before_files,
              "probeLogits": logits.tolist(), "seconds": time.perf_counter() - started,
              "peakGpuGiB": mx.get_peak_memory() / 2**30}
    if parent_hashes:
        result.update(parentAdapterSha256=parent_hashes["adapters.safetensors"], parentAdapterFiles=parent_hashes)
    report_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: result[key] for key in ("status", "iterations", "adapterTensors", "seconds", "peakGpuGiB")}), flush=True)


if __name__ == "__main__":
    main()
