"""분석 모델 이름을 관리자가 등록한 로컬 MLX 모델과 학습기에 연결한다."""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path
from uuid import UUID

from dataset_contract import digest, strict_json
from training_profiles import GENERIC_TRAINER, QWEN_PROFILE, ProfileError, validate_profile

MODEL_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:/@+\-]{0,199}\Z")
QWEN_SOURCE = "qwen3.8:27b"
QWEN_BASE = "mlx-community/Qwen3.8-27B-4bit"
QWEN_TRAINER = "qwen-mlp-trial-v1"


class RegistryError(ValueError):
    """설정 원문과 로컬 경로를 외부에 공개하지 않는 등록 오류다."""


@dataclass(frozen=True)
class ModelBinding:
    """한 요청에 고정한 기반 모델과 학습기 정보다."""
    directory: Path
    target: dict
    profile: dict = field(default_factory=dict)
    resume_job_id: str | None = None


def read_json(path):
    """작은 로컬 설정 파일만 읽고 심볼릭 링크는 거부한다."""
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 128 * 1024:
        raise RegistryError("로컬 모델 설정 파일을 확인하지 못했습니다.")
    try:
        value = strict_json(path.read_text())
    except (ValueError, UnicodeError):
        raise RegistryError("모델 설정의 JSON 형식을 확인해 주세요.") from None
    if not isinstance(value, dict):
        raise RegistryError("모델 설정의 형식을 확인해 주세요.")
    return value


def resolve(source_model, default_directory=None, registry_file=None):
    """정확히 등록한 이름만 연결하며 미등록 모델을 기본 Qwen으로 대체하지 않는다."""
    if not isinstance(source_model, str) or not MODEL_NAME.fullmatch(source_model):
        raise RegistryError("분석 모델 식별자를 확인하지 못했습니다.")
    entries = {}
    if default_directory:
        entries[QWEN_SOURCE] = {"directory": str(default_directory), "baseModel": QWEN_BASE, "trainer": QWEN_TRAINER}
    if registry_file:
        location = Path(registry_file)
        if not location.is_absolute():
            raise RegistryError("모델 대응 설정 파일은 절대 경로로 지정하세요.")
        configured = read_json(location)
        if len(configured) > 100:
            raise RegistryError("모델 대응 설정의 항목 수를 확인해 주세요.")
        for name, entry in configured.items():
            if (not MODEL_NAME.fullmatch(name) or not isinstance(entry, dict)
                    or not {"directory", "baseModel", "trainer"} <= entry.keys()
                    or entry.keys() - {"directory", "baseModel", "trainer", "profile", "resumeJobId"}
                    or not all(isinstance(entry[key], str) for key in ("directory", "baseModel", "trainer"))
                    or not MODEL_NAME.fullmatch(entry["baseModel"])
                    or not re.fullmatch(r"[a-z0-9-]{1,60}", entry["trainer"])):
                raise RegistryError("모델 대응 설정의 항목 형식을 확인해 주세요.")
        entries.update(configured)
    entry = entries.get(source_model)
    if not entry:
        raise RegistryError("선택한 분석 모델에 대응하는 로컬 MLX 모델이 등록되지 않았습니다.")
    directory = Path(entry["directory"])
    if not directory.is_absolute() or directory.is_symlink() or not directory.is_dir():
        raise RegistryError("등록된 MLX 모델 폴더를 확인하지 못했습니다.")
    manifest = read_json(directory / "download_manifest.json")
    if (manifest.get("modelId") != entry["baseModel"]
            or not isinstance(manifest.get("revision"), str)
            or not re.fullmatch(r"[0-9a-f]{40}", manifest["revision"])):
        raise RegistryError("등록된 기반 모델과 다운로드 명세가 일치하지 않습니다.")
    target = {"sourceModel": source_model, "baseModel": entry["baseModel"], "revision": manifest["revision"], "trainer": entry["trainer"]}
    profile = {}
    if "profile" in entry:
        try:
            profile = validate_profile(entry["profile"])
        except ProfileError:
            raise RegistryError("등록된 모델의 학습 프로필을 확인해 주세요.") from None
        config = read_json(directory / "config.json")
        if config.get("model_type") != profile["modelType"]:
            raise RegistryError("학습 프로필과 실제 모델 구조가 일치하지 않습니다.")
    elif entry["trainer"] == GENERIC_TRAINER:
        raise RegistryError("공통 MLX 학습기는 모델별 학습 프로필을 명시해야 합니다.")
    elif (entry["trainer"], entry["baseModel"], source_model) == (QWEN_TRAINER, QWEN_BASE, QWEN_SOURCE):
        profile = {**QWEN_PROFILE, "loraKeys": list(QWEN_PROFILE["loraKeys"])}
    if entry["trainer"] == QWEN_TRAINER and profile != QWEN_PROFILE:
        raise RegistryError("기존 Qwen 학습기는 고정된 시험 설정만 받습니다. 설정을 바꾸려면 공통 학습기를 등록하세요.")
    resume = entry.get("resumeJobId")
    if "resumeJobId" in entry:
        try:
            if not isinstance(resume, str) or str(UUID(resume)) != resume:
                raise ValueError()
        except ValueError:
            raise RegistryError("이어 학습할 완료 작업의 식별자를 확인하세요.") from None
    # 같은 기반 버전이라도 경로나 학습기 대응이 바뀌면 이전 화면의 시작 요청을 거부한다.
    pinned = {**target, "directory": str(directory)}
    if "profile" in entry:
        pinned["profile"] = profile
    if resume is not None:
        pinned["resumeJobId"] = resume
    return ModelBinding(directory, {**target, "digest": digest(pinned)}, profile, resume)
