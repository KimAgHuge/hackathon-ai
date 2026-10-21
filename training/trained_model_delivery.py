"""학습을 반영한 GGUF 모델을 서명된 패키지로 만들어 공유 폴더에 게시한다."""

import fcntl
import hashlib
import hmac
import os
import re
import shutil
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from uuid import UUID

from model_delivery import (PackageError, canonical, check_secret, directory, model_identity,
                            read_json, sync_directory, write_file)
from model_registry import MODEL_NAME, QWEN_BASE, QWEN_SOURCE
from model_export_profiles import GENERIC_EXPORTER, export_capability
from training_profiles import ProfileError, validate_profile

DOMAIN = b"doenaai-model-gguf-delivery-v1\n"
DOMAIN_V2 = b"doenaai-model-gguf-delivery-v2\n"
PAYLOAD_FILES = ("model.gguf", "evaluation-summary.json")
PACKAGE_FILES = (*PAYLOAD_FILES, "manifest.json", "READY.json")
MAX_ARTIFACT_BYTES = 64 * 1024**3
HASH = re.compile(r"[0-9a-f]{64}\Z")
LLAMA_CPP_REVISION = "620d2225cc436ab0b001e3200e590e3b286ef9ab"
MLX_LM_VERSION = "0.32.0"
BASE_MODEL_REVISION = "10c35caafbb80f7dc6a7a432cdd11af10a6d4818"
METADATA = {"jobId", "version", "manifestSha256", "sourceModel", "baseModel", "baseRevision",
            "model", "artifactSha256", "artifactSize", "quantization"}
GENERIC_METADATA = METADATA | {"formatVersion", "modelType", "architecture", "profile"}


def generic_provenance(value):
    """새 형식은 원래 분석 모델과 MLX 식별자·변환 구조·전체 학습 설정을 함께 확인한다."""
    try:
        profile = validate_profile(value.get("profile"))
        supported = export_capability(profile, base_model=value.get("baseModel"), revision=value.get("baseRevision"))
        model_identity({"modelId": value.get("baseModel"), "revision": value.get("baseRevision")})
        if (not supported["supported"] or profile.get("exporter") != GENERIC_EXPORTER
                or type(value.get("formatVersion")) is not int or value["formatVersion"] != 2
                or not isinstance(value.get("sourceModel"), str) or not MODEL_NAME.fullmatch(value["sourceModel"])
                or value.get("modelType") != profile["modelType"] or value.get("architecture") != profile["exportArchitecture"]):
            raise ValueError()
        return profile
    except (ValueError, TypeError, KeyError, ProfileError):
        raise PackageError("모델 패키지의 기반 식별자와 모델별 변환 프로필이 일치하지 않습니다.") from None


def checksum(path, limit=MAX_ARTIFACT_BYTES, *, check=None):
    """대형 모델을 메모리에 올리지 않고 고정 크기로 나눠 검사한다."""
    path = Path(path)
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= limit:
        raise PackageError("모델 파일의 형식과 크기를 확인하세요.")
    value, size = hashlib.sha256(), 0
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            if check:
                check()
            value.update(chunk)
            size += len(chunk)
            if size > limit:
                raise PackageError("모델 패키지의 파일 크기 제한을 초과했습니다.")
    return value.hexdigest(), size


def copy_file(source, destination, *, check=None):
    """대형 파일을 새 경로에 복사하고 기존 파일은 덮어쓰지 않는다."""
    expected = checksum(source, check=check)
    descriptor = os.open(destination, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "wb") as target, Path(source).open("rb") as stream:
        copied = 0
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            if check:
                check()
            copied += len(chunk)
            if copied > expected[1]:
                raise PackageError("복사 중 모델 파일 크기가 변경됐습니다.")
            target.write(chunk)
        target.flush()
        os.fsync(target.fileno())
    if checksum(destination, check=check) != expected:
        raise PackageError("모델 파일 복사 중 내용이 변경됐습니다.")


def identity(identifier):
    try:
        if not isinstance(identifier, str) or str(UUID(identifier)) != identifier:
            raise ValueError()
    except (ValueError, AttributeError):
        raise PackageError("모델 생성 작업 식별자를 확인하세요.") from None
    return "trained-web-" + identifier, "doenaai-trained:web-" + identifier


def validate_metadata(value):
    """원격 요청에는 파일 경로와 명령을 받지 않고 고정 식별 정보만 받는다."""
    if not isinstance(value, dict) or set(value) not in (METADATA, GENERIC_METADATA):
        raise PackageError("모델 패키지 식별 정보의 형식을 확인하세요.")
    generic = set(value) == GENERIC_METADATA
    if generic:
        generic_provenance(value)
    version, model = identity(value.get("jobId"))
    if (value.get("version") != version or value.get("model") != model
            or (not generic and (value.get("sourceModel") != QWEN_SOURCE or value.get("baseModel") != QWEN_BASE))
            or not isinstance(value.get("baseRevision"), str) or not re.fullmatch(r"[0-9a-f]{40}", value["baseRevision"])
            or value.get("quantization") != "Q4_K_M"
            or any(not isinstance(value.get(key), str) or not HASH.fullmatch(value[key])
                   for key in ("manifestSha256", "artifactSha256"))
            or type(value.get("artifactSize")) is not int or not 24 <= value["artifactSize"] <= MAX_ARTIFACT_BYTES):
        raise PackageError("모델 패키지 식별 정보가 지원하는 형식과 다릅니다.")
    return dict(value)


def verify_package(package_dir, secret, *, check=None):
    """서명과 모든 고정 파일의 해시를 검사한 뒤 공개 가능한 식별 정보만 반환한다."""
    check_secret(secret)
    package = directory(package_dir)
    manifest, ready = read_json(package / "manifest.json"), read_json(package / "READY.json")
    generic = type(manifest.get("formatVersion")) is int and manifest["formatVersion"] == 2
    format_version, domain = (2, DOMAIN_V2) if generic else (1, DOMAIN)
    version, model = identity(manifest.get("jobId"))
    manifest_hash = checksum(package / "manifest.json", 1024**2, check=check)[0]
    signature = hmac.new(secret.encode(), domain + canonical(manifest), hashlib.sha256).hexdigest()
    if (set(ready) != {"formatVersion", "version", "manifestSha256", "signature"}
            or type(ready.get("formatVersion")) is not int or ready["formatVersion"] != format_version
            or ready.get("version") != version or ready.get("manifestSha256") != manifest_hash
            or not isinstance(ready.get("signature"), str) or not hmac.compare_digest(ready["signature"], signature)):
        raise PackageError("모델 패키지 완료 표시의 서명과 해시가 일치하지 않습니다.")
    expected_fields = {"formatVersion", "version", "jobId", "packageType", "sourceModel", "model", "runtimeModel",
                       "quantization", "files", "productionApproved", "qualityEvaluation", "ollamaCompatible", "toolchain", "createdAt"}
    if generic:
        expected_fields |= {"modelType", "architecture", "profile"}
    if (set(manifest) != expected_fields or type(manifest.get("formatVersion")) is not int or manifest["formatVersion"] != format_version
            or manifest.get("packageType") != "MODEL_GGUF" or manifest.get("version") != version
            or manifest.get("runtimeModel") != model or (not generic and manifest.get("sourceModel") != QWEN_SOURCE)
            or manifest.get("quantization") != "Q4_K_M" or manifest.get("productionApproved") is not False
            or manifest.get("qualityEvaluation") != "NOT_EVALUATED" or manifest.get("ollamaCompatible") is not True
            or manifest.get("toolchain") != {"mlxLmVersion": MLX_LM_VERSION, "llamaCppRevision": LLAMA_CPP_REVISION}):
        raise PackageError("지원하는 새 모델 패키지 형식이 아닙니다.")
    base = model_identity(manifest.get("model"))
    if not generic and base["modelId"] != QWEN_BASE:
        raise PackageError("모델 패키지의 기반 모델이 지원 대상과 다릅니다.")
    provenance = {}
    if generic:
        provenance = {"formatVersion": 2, "modelType": manifest["modelType"], "architecture": manifest["architecture"], "profile": manifest["profile"]}
        generic_provenance({**provenance, "sourceModel": manifest["sourceModel"], "baseModel": base["modelId"], "baseRevision": base["revision"]})
    entries = manifest.get("files")
    if not isinstance(entries, list) or len(entries) != 2:
        raise PackageError("모델 패키지 파일 명세가 올바르지 않습니다.")
    seen, artifact = set(), None
    for entry in entries:
        if (not isinstance(entry, dict) or set(entry) != {"path", "size", "sha256"}
                or entry.get("path") not in PAYLOAD_FILES or entry["path"] in seen
                or type(entry.get("size")) is not int or not isinstance(entry.get("sha256"), str)
                or not HASH.fullmatch(entry["sha256"])):
            raise PackageError("허용하지 않은 모델 패키지 파일 명세입니다.")
        seen.add(entry["path"])
        actual, size = checksum(package / entry["path"], MAX_ARTIFACT_BYTES if entry["path"] == "model.gguf" else 1024**2, check=check)
        if (actual, size) != (entry["sha256"], entry["size"]):
            raise PackageError("모델 패키지 파일의 해시와 크기가 일치하지 않습니다.")
        if entry["path"] == "model.gguf":
            artifact = entry
    with (package / "model.gguf").open("rb") as stream:
        if stream.read(4) != b"GGUF":
            raise PackageError("새 모델 파일이 GGUF 형식이 아닙니다.")
    if generic:
        from model_export_worker import gguf_identity, ExportError
        try:
            actual_identity = gguf_identity(package / "model.gguf")
            if (actual_identity.get("general.architecture") != manifest["architecture"]
                    or actual_identity.get("general.file_type") != 15
                    or actual_identity.get("general.type", "model") != "model"):
                raise ExportError("모델 구조가 패키지 명세와 다릅니다.")
        except (ExportError, OSError):
            raise PackageError("새 모델의 실제 GGUF 구조와 서명된 변환 명세가 다릅니다.") from None
    summary = read_json(package / "evaluation-summary.json")
    if summary.get("qualityEvaluation") != "NOT_EVALUATED" or summary.get("productionApproved") is not False:
        raise PackageError("모델 품질 평가 안내가 올바르지 않습니다.")
    return validate_metadata({"jobId": manifest["jobId"], "version": version, "model": model,
        "manifestSha256": manifest_hash, "sourceModel": manifest["sourceModel"], "baseModel": base["modelId"],
        "baseRevision": base["revision"], "artifactSha256": artifact["sha256"], "artifactSize": artifact["size"], "quantization": "Q4_K_M", **provenance})


def create_package(artifact, summary, output, identifier, base, secret, *, check=None, source_model=None, profile=None):
    """검증을 마친 생성 모델만 새 로컬 패키지로 보관한다."""
    check_secret(secret)
    version, model = identity(identifier)
    base = model_identity(base)
    format_version, domain, provenance = 1, DOMAIN, {}
    if profile is not None:
        try:
            profile = validate_profile(profile)
        except ProfileError:
            raise PackageError("새 모델 패키지에 사용할 학습 프로필을 확인하세요.") from None
        provenance = {"formatVersion": 2, "modelType": profile.get("modelType"),
                      "architecture": profile.get("exportArchitecture"), "profile": profile}
        generic_provenance({**provenance, "sourceModel": source_model, "baseModel": base["modelId"], "baseRevision": base["revision"]})
        format_version, domain = 2, DOMAIN_V2
    elif source_model not in (None, QWEN_SOURCE):
        raise PackageError("기존 형식은 검증한 Qwen 분석 모델에만 사용할 수 있습니다.")
    output = Path(output)
    if output.exists() or output.is_symlink():
        raise PackageError("새 모델 패키지 출력 경로가 이미 있습니다.")
    directory(output.parent, create=True)
    temporary = Path(tempfile.mkdtemp(prefix=".model-package-", dir=output.parent))
    try:
        copy_file(artifact, temporary / "model.gguf", check=check)
        write_file(temporary / "evaluation-summary.json", canonical(summary))
        entries = []
        for name in PAYLOAD_FILES:
            value, size = checksum(temporary / name, check=check)
            entries.append({"path": name, "sha256": value, "size": size})
        manifest = {"formatVersion": format_version, "version": version, "jobId": identifier, "packageType": "MODEL_GGUF",
            "sourceModel": source_model if profile is not None else QWEN_SOURCE, "model": base, "runtimeModel": model, "quantization": "Q4_K_M",
            "files": entries, "productionApproved": False, "qualityEvaluation": "NOT_EVALUATED", "ollamaCompatible": True,
            "toolchain": {"mlxLmVersion": MLX_LM_VERSION, "llamaCppRevision": LLAMA_CPP_REVISION}, "createdAt": datetime.now(timezone.utc).isoformat(), **provenance}
        write_file(temporary / "manifest.json", canonical(manifest))
        write_file(temporary / "READY.json", canonical({"formatVersion": format_version, "version": version,
            "manifestSha256": checksum(temporary / "manifest.json")[0],
            "signature": hmac.new(secret.encode(), domain + canonical(manifest), hashlib.sha256).hexdigest()}))
        verify_package(temporary, secret, check=check)
        sync_directory(temporary)
        temporary.rename(output)
        sync_directory(output.parent)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)
    return verify_package(output, secret, check=check)


def publish_package(package_dir, shared_dir, secret, *, check=None):
    """동일 버전의 내용은 재사용하고 공유 폴더의 다른 내용은 덮어쓰지 않는다."""
    package = directory(package_dir)
    metadata = verify_package(package, secret, check=check)
    root = directory(directory(shared_dir) / "model-deliveries", create=True)
    destination = root / metadata["version"]
    descriptor = os.open(package / ".publish.lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    temporary = None
    try:
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise PackageError("다른 모델 게시 작업이 진행 중입니다. 완료 후 다시 시도하세요.") from None
        if destination.exists() or destination.is_symlink():
            if verify_package(destination, secret, check=check) != metadata:
                raise PackageError("같은 버전의 다른 모델 패키지가 있어 기존 파일을 보존했습니다.")
        else:
            temporary = Path(tempfile.mkdtemp(prefix=".model-publish-", suffix=".uploading", dir=root))
            for name in PACKAGE_FILES:
                copy_file(package / name, temporary / name, check=check)
            if verify_package(temporary, secret, check=check) != metadata:
                raise PackageError("게시 중 모델 패키지 내용이 변경됐습니다.")
            sync_directory(temporary)
            temporary.rename(destination)
            sync_directory(root)
    finally:
        if temporary is not None and temporary.exists():
            shutil.rmtree(temporary)
        os.close(descriptor)
    return metadata
