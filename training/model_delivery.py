"""학습 어댑터와 검증 요약만 버전별 패키지로 묶어 iCloud 공유 폴더에 게시한다."""

import argparse
import fcntl
import hashlib
import hmac
import json
import math
import os
import re
import shutil
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from receiver_config import load_receiver_env

ROOT = Path(__file__).resolve().parent
PAYLOAD_FILES = ("adapters.safetensors", "adapter_config.json", "evaluation-summary.json")
PACKAGE_FILES = (*PAYLOAD_FILES, "manifest.json", "READY.json")
VERSION = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}\Z")
HASH = re.compile(r"[0-9a-f]{64}\Z")
PURPOSE = "approved_case_smoke_test_do_not_deploy"
DOMAIN = b"doenaai-model-delivery-v1\n"


class PackageError(ValueError):
    """원본 자료나 비밀값을 포함하지 않는 패키지 오류다."""


def canonical(value):
    """항상 같은 JSON 바이트로 해시와 서명을 계산한다."""
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False) + "\n").encode()


def check_secret(secret):
    if not isinstance(secret, str) or len(secret) < 32:
        raise PackageError("두 서버가 공유하는 32자 이상의 전송 키를 설정하세요.")


def directory(path, *, create=False):
    """지정한 폴더가 존재하며 폴더 자체가 심볼릭 링크가 아닌지 확인한다."""
    path = Path(path)
    if path.is_symlink():
        raise PackageError("패키지 폴더에 심볼릭 링크를 사용할 수 없습니다.")
    if create:
        path.mkdir(mode=0o700, parents=True, exist_ok=True)
    if not path.is_dir():
        raise PackageError("설정한 패키지·공유 폴더가 연결돼 있지 않습니다.")
    return path


def regular(path, limit=512 * 1024**2):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= limit:
        raise PackageError("필수 파일이 없거나 파일 형식·크기가 올바르지 않습니다.")
    return path


def checksum(path):
    value, size = hashlib.sha256(), 0
    with regular(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            value.update(chunk)
            size += len(chunk)
            if size > 512 * 1024**2:
                raise PackageError("파일이 512 MiB 제한을 초과했습니다.")
    return value.hexdigest(), size


def read_json(path):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise PackageError("JSON에 중복 필드가 있습니다.")
            result[key] = value
        return result

    def invalid(_):
        raise PackageError("JSON에 유효하지 않은 숫자가 있습니다.")

    try:
        value = json.loads(regular(path, 1024**2).read_text(encoding="utf-8"),
                           object_pairs_hook=pairs, parse_constant=invalid)
    except (UnicodeError, json.JSONDecodeError):
        raise PackageError("패키지 JSON 형식을 확인하세요.") from None
    if not isinstance(value, dict):
        raise PackageError("패키지 JSON은 객체여야 합니다.")
    return value


def write_file(path, data):
    """새 파일만 만들고 내용을 디스크에 반영한다."""
    descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(descriptor, "wb") as target:
        target.write(data)
        target.flush()
        os.fsync(target.fileno())


def copy_file(source, destination):
    """가중치를 메모리에 모두 올리지 않고 새 파일로 복사한다."""
    with regular(source).open("rb") as stream:
        descriptor = os.open(destination, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, "wb") as target:
            size = 0
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                size += len(chunk)
                if size > 512 * 1024**2:
                    raise PackageError("복사할 파일이 512 MiB 제한을 초과했습니다.")
                target.write(chunk)
            target.flush()
            os.fsync(target.fileno())


def sync_directory(path):
    descriptor = os.open(path, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def check_version(version):
    if not isinstance(version, str) or not VERSION.fullmatch(version) or ".." in version:
        raise PackageError("버전은 영문·숫자로 시작하는 80자 이하의 영문·숫자·점·밑줄·하이픈으로 지정하세요.")


def finite(value):
    if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
        raise PackageError("실행·검증 보고서의 숫자를 확인하세요.")
    return value


def model_identity(value):
    if (not isinstance(value, dict)
            or not isinstance(value.get("modelId"), str)
            or not re.fullmatch(r"[A-Za-z0-9._-]+/[A-Za-z0-9._-]+", value["modelId"])
            or not isinstance(value.get("revision"), str)
            or not re.fullmatch(r"[0-9a-f]{40}", value["revision"])):
        raise PackageError("기반 모델의 정확한 ID와 40자리 revision이 필요합니다.")
    return {key: value[key] for key in ("modelId", "revision")}


def execution_summary(adapter_dir, report_dir):
    """허용한 실행 숫자만 남기고 사례 원문·로컬 경로·확인 출력은 포함하지 않는다."""
    weights = adapter_dir / "adapters.safetensors"
    config = read_json(adapter_dir / "adapter_config.json")
    training = read_json(report_dir / "training-report.json")
    reload = read_json(report_dir / "reload-report.json")
    dataset = read_json(report_dir / "manifest.json")
    if (training.get("status") != "complete" or reload.get("status") != "complete"
            or training.get("purpose") != PURPOSE or config.get("purpose") != PURPOSE
            or config.get("fine_tune_type") != "lora" or training.get("protectedFilesUnchanged") is not True):
        raise PackageError("학습·재로딩·원본 보존 검사가 완료된 시험 어댑터만 묶을 수 있습니다.")
    if set(config) != {"fine_tune_type", "num_layers", "lora_parameters", "purpose"}:
        raise PackageError("어댑터 설정에 허용하지 않은 필드가 있습니다.")
    lora = config.get("lora_parameters")
    if (not isinstance(lora, dict) or set(lora) != {"rank", "scale", "dropout", "keys"}
            or type(config.get("num_layers")) is not int or not 1 <= config["num_layers"] <= 128
            or type(lora.get("rank")) is not int or not 1 <= lora["rank"] <= 256
            or not isinstance(lora.get("keys"), list) or not lora["keys"]
            or any(not isinstance(key, str) or not re.fullmatch(r"[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*", key) for key in lora["keys"])):
        raise PackageError("LoRA 설정을 확인하세요.")
    finite(lora["scale"])
    if finite(lora["dropout"]) >= 1:
        raise PackageError("LoRA dropout 값을 확인하세요.")
    if (training.get("rank") != lora["rank"] or training.get("layers") != config["num_layers"]
            or training.get("trainableModules") != lora["keys"]):
        raise PackageError("어댑터 설정과 학습 보고서가 일치하지 않습니다.")
    if checksum(weights)[0] != training.get("adapterSha256"):
        raise PackageError("학습 후 어댑터 파일의 해시가 달라졌습니다.")
    if checksum(report_dir / "manifest.json")[0] != training.get("datasetManifestSha256"):
        raise PackageError("학습 자료 명세가 실행 당시와 다릅니다.")
    model = model_identity(training.get("model"))
    if not isinstance(training.get("contractSha256"), str) or not HASH.fullmatch(training["contractSha256"]):
        raise PackageError("입력 계약의 SHA-256 형식을 확인하세요.")
    if model != model_identity(dataset.get("model")) or training.get("contractSha256") != dataset.get("contractSha256"):
        raise PackageError("학습 자료와 보고서의 모델·입력 계약이 다릅니다.")
    count = training.get("iterations")
    steps = training.get("steps")
    if type(count) is not int or count < 1 or not isinstance(steps, list) or len(steps) != count:
        raise PackageError("학습 업데이트 기록이 불완전합니다.")
    for i, step in enumerate(steps, 1):
        if (not isinstance(step, dict) or type(step.get("step")) is not int
                or step["step"] != i or finite(step.get("gradientNorm")) <= 0):
            raise PackageError("학습 업데이트 기록이 올바르지 않습니다.")
        finite(step.get("loss"))
    if (type(training.get("adapterTensors")) is not int or training["adapterTensors"] < 1
            or type(reload.get("adapterTensors")) is not int or reload["adapterTensors"] != training["adapterTensors"]
            or finite(reload.get("maxLogitDifference")) > 0.02):
        raise PackageError("어댑터 재로딩 검사 결과가 유효하지 않습니다.")
    counts = dataset.get("stageSampleCounts")
    if (not isinstance(counts, dict) or set(counts) != {"train", "valid", "test"}
            or any(type(value) is not int or value < 0 for value in counts.values()) or not counts["train"]):
        raise PackageError("학습·검증·시험 자료 수를 확인하세요.")
    validation = {}
    for key in ("validationLossBefore", "validationLossAfter"):
        value = training.get(key)
        if not isinstance(value, dict) or set(value) != {"valid"}:
            raise PackageError("검증 손실 기록을 확인하세요.")
        validation[key] = {"valid": None if value["valid"] is None else finite(value["valid"])}
    summary = {"purpose": PURPOSE, "qualityEvaluation": "NOT_EVALUATED", "productionApproved": False,
               "iterations": count, "layers": config["num_layers"], "rank": lora["rank"],
               "trainableModules": lora["keys"], "sampleCounts": counts,
               "trainingLossFirst": steps[0]["loss"], "trainingLossLast": steps[-1]["loss"],
               "peakGpuGiB": finite(training.get("peakGpuGiB")), "seconds": finite(training.get("seconds")),
               "reloadedTensors": training["adapterTensors"], "reloadMaxDifference": reload["maxLogitDifference"],
               "contractSha256": training["contractSha256"], **validation,
               "notice": "실행·재로딩 확인 자료입니다. 답변 품질 평가나 운영 배포 승인이 아닙니다."}
    return config, model, summary


def verify_package(package_dir, secret):
    """완료 표시의 서명과 고정 파일 모두를 검사하며 공유 폴더의 명령을 실행하지 않는다."""
    check_secret(secret)
    package = directory(package_dir)
    manifest, ready = read_json(package / "manifest.json"), read_json(package / "READY.json")
    version = manifest.get("version")
    check_version(version)
    manifest_hash = checksum(package / "manifest.json")[0]
    expected = hmac.new(secret.encode(), DOMAIN + canonical(manifest), hashlib.sha256).hexdigest()
    if (set(ready) != {"formatVersion", "version", "manifestSha256", "signature"}
            or type(ready.get("formatVersion")) is not int or ready["formatVersion"] != 1 or ready.get("version") != version
            or ready.get("manifestSha256") != manifest_hash
            or not isinstance(ready.get("signature"), str)
            or not hmac.compare_digest(ready["signature"], expected)):
        raise PackageError("패키지 완료 표시의 해시 또는 서명이 일치하지 않습니다.")
    if (type(manifest.get("formatVersion")) is not int or manifest["formatVersion"] != 1
            or manifest.get("packageType") != "MLX_LORA_ADAPTER"
            or manifest.get("productionApproved") is not False or manifest.get("ollamaCompatible") is not False
            or manifest.get("qualityEvaluation") != "NOT_EVALUATED"):
        raise PackageError("지원하는 시험 패키지 형식이 아닙니다.")
    model_identity(manifest.get("model"))
    entries = manifest.get("files")
    if not isinstance(entries, list) or len(entries) != len(PAYLOAD_FILES):
        raise PackageError("패키지 파일 명세가 올바르지 않습니다.")
    names = set()
    for item in entries:
        if (not isinstance(item, dict) or set(item) != {"path", "size", "sha256"}
                or item.get("path") not in PAYLOAD_FILES or item["path"] in names
                or type(item.get("size")) is not int or not isinstance(item.get("sha256"), str)
                or not HASH.fullmatch(item["sha256"])):
            raise PackageError("허용하지 않은 파일 경로나 해시가 포함됐습니다.")
        names.add(item["path"])
        actual, size = checksum(package / item["path"])
        if actual != item["sha256"] or size != item["size"]:
            raise PackageError("파일 크기·해시가 일치하지 않습니다. 동기화 완료를 확인하세요.")
    return {"status": "VERIFIED_TRIAL_ONLY", "version": version, "manifestSha256": manifest_hash,
            "package": str(package), "files": len(entries), "productionApproved": False}


def create_package(adapter_dir, report_dir, output_dir, version, secret):
    """검증된 원본을 보존하고 새 로컬 패키지만 원자적으로 만든다."""
    check_secret(secret)
    check_version(version)
    adapter, reports = directory(adapter_dir), directory(report_dir)
    output = Path(output_dir)
    if output.exists() or output.is_symlink():
        raise PackageError("패키지 출력 폴더가 이미 있습니다. 새 버전 경로를 사용하세요.")
    config, model, summary = execution_summary(adapter, reports)
    directory(output.parent, create=True)
    temporary = Path(tempfile.mkdtemp(prefix=".package-", dir=output.parent))
    try:
        copy_file(adapter / "adapters.safetensors", temporary / "adapters.safetensors")
        write_file(temporary / "adapter_config.json", canonical(config))
        write_file(temporary / "evaluation-summary.json", canonical(summary))
        entries = []
        for name in PAYLOAD_FILES:
            value, size = checksum(temporary / name)
            entries.append({"path": name, "size": size, "sha256": value})
        if entries[0]["sha256"] != read_json(reports / "training-report.json")["adapterSha256"]:
            raise PackageError("복사 중 어댑터 파일이 변경됐습니다.")
        manifest = {"formatVersion": 1, "version": version, "packageType": "MLX_LORA_ADAPTER",
                    "model": model, "createdAt": datetime.now(timezone.utc).isoformat(), "files": entries,
                    "productionApproved": False, "ollamaCompatible": False, "qualityEvaluation": "NOT_EVALUATED"}
        write_file(temporary / "manifest.json", canonical(manifest))
        ready = {"formatVersion": 1, "version": version, "manifestSha256": checksum(temporary / "manifest.json")[0],
                 "signature": hmac.new(secret.encode(), DOMAIN + canonical(manifest), hashlib.sha256).hexdigest()}
        write_file(temporary / "READY.json", canonical(ready))
        verify_package(temporary, secret)
        sync_directory(temporary)
        temporary.rename(output)
        sync_directory(output.parent)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)
    return verify_package(output, secret)


def publish_package(package_dir, shared_dir, secret):
    """준비한 버전을 공유 폴더에 게시하며 동일 버전의 다른 내용은 덮어쓰지 않는다."""
    package, shared = directory(package_dir), directory(shared_dir)
    info = verify_package(package, secret)
    root = directory(shared / "model-deliveries", create=True)
    destination = root / info["version"]
    lock_fd = os.open(package / ".publish.lock", os.O_CREAT | os.O_RDWR, 0o600)
    temporary = None
    try:
        fcntl.flock(lock_fd, fcntl.LOCK_EX)
        status = "PUBLISHED"
        if destination.exists() or destination.is_symlink():
            if verify_package(destination, secret)["manifestSha256"] != info["manifestSha256"]:
                raise PackageError("같은 버전에 다른 패키지가 있습니다. 기존 파일을 보존했습니다.")
            status = "ALREADY_PUBLISHED"
        else:
            temporary = Path(tempfile.mkdtemp(prefix=f".{info['version']}-", suffix=".uploading", dir=root))
            # 완료 표시는 마지막에 복사하지만 동기화 순서는 수신 측에서 다시 검증해야 한다.
            for name in PACKAGE_FILES:
                copy_file(package / name, temporary / name)
            verify_package(temporary, secret)
            sync_directory(temporary)
            try:
                temporary.rename(destination)
            except OSError:
                if not destination.is_dir() or verify_package(destination, secret)["manifestSha256"] != info["manifestSha256"]:
                    raise PackageError("동일 버전의 다른 게시가 감지돼 덮어쓰지 않았습니다.") from None
                status = "ALREADY_PUBLISHED"
            sync_directory(root)
        result = {**info, "status": status, "package": str(destination),
                  "deliveryState": "WAITING_FOR_REMOTE_VERIFICATION", "cloudUploadVerified": False}
        state = package / ".publish-state.json"
        descriptor, name = tempfile.mkstemp(prefix=".publish-state-", dir=package)
        with os.fdopen(descriptor, "wb") as target:
            target.write(canonical(result))
            target.flush()
            os.fsync(target.fileno())
        os.replace(name, state)
        sync_directory(package)
        return result
    finally:
        if temporary is not None and temporary.exists():
            shutil.rmtree(temporary)
        fcntl.flock(lock_fd, fcntl.LOCK_UN)
        os.close(lock_fd)


def main():
    """비밀키는 기존 로컬 환경에서만 읽고 명령행에는 넣지 않는다."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env")
    commands = parser.add_subparsers(dest="action", required=True)
    stage = commands.add_parser("stage", help="새 시험 패키지 준비")
    stage.add_argument("--adapter", type=Path, required=True)
    stage.add_argument("--reports", type=Path, required=True)
    stage.add_argument("--output", type=Path, required=True)
    stage.add_argument("--version", required=True)
    for action in ("publish", "verify"):
        command = commands.add_parser(action, help="공유 폴더 게시" if action == "publish" else "패키지 검증")
        command.add_argument("--package", type=Path, required=True)
        if action == "publish":
            command.add_argument("--shared-dir", type=Path)
    args = parser.parse_args()
    try:
        load_receiver_env(args.env_file)
        secret = os.environ.get("DOENAAI_TRAINING_TRANSFER_KEY", "")
        if args.action == "stage":
            result = create_package(args.adapter, args.reports, args.output, args.version, secret)
        elif args.action == "verify":
            result = verify_package(args.package, secret)
        else:
            shared = args.shared_dir or os.environ.get("DOENAAI_TRAINING_SHARED_DIR")
            if not shared:
                raise PackageError("iCloud 공유 폴더의 경로를 설정하세요.")
            result = publish_package(args.package, shared, secret)
    except (PackageError, OSError, ValueError, TypeError, KeyError):
        parser.exit(1, "패키지 처리 실패: 파일·보고서·서명 키·폴더 설정을 확인하세요. 원본은 보존했습니다.\n")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
