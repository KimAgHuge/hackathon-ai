"""검증된 수신 사례만 고정 도구로 학습하고 작업 상태를 안전하게 보관한다."""

import fcntl
import importlib.util
import json
import logging
import math
import os
import re
import signal
import subprocess
import sys
import threading
import time
from dataclasses import replace
from datetime import datetime, timezone
from pathlib import Path
from uuid import UUID

from dataset_contract import DatasetError, digest, read_jsonl, validate_case
from gpu_lease import GpuLease
from shared_transfer import HASH, write_json_atomic
from model_registry import RegistryError, resolve, QWEN_SOURCE, QWEN_BASE, QWEN_TRAINER
from training_profiles import GENERIC_TRAINER
from model_export_profiles import export_capability
from model_delivery import (PackageError, create_package, execution_summary, publish_package,
                            verify_package, read_json as delivery_json, checksum as adapter_checksum)
from transfer_cases import TransferError, content_hash, private_directory

ROOT = Path(__file__).resolve().parent
DERIVED_MODEL = re.compile(r"doenaai-(?:trained|mlx):web-([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})\Z")
ACTIVE = {"PENDING", "PREPARING", "TRAINING", "VERIFYING"}
TERMINAL = {"SUCCEEDED", "FAILED", "CANCELLED"}
MESSAGES = {
    "PENDING": "학습 작업을 접수했습니다.",
    "PREPARING": "검증된 승인 자료를 원 요구사항별로 나누고 학습 입력을 준비합니다.",
    "TRAINING": "일반 분석 자료로 제한된 시험 학습을 진행합니다. 운영 모델은 변경하지 않습니다.",
    "VERIFYING": "새 어댑터를 별도 프로세스로 다시 불러와 저장 결과를 확인합니다.",
    "SUCCEEDED": "시험 학습과 어댑터 재로딩을 완료했습니다. 독립 시험·업무 품질 평가와 운영 배포는 수행하지 않았습니다.",
    "CANCELLED": "학습 작업을 취소했습니다. 기존 모델과 앞선 산출물은 유지합니다.",
    "FAILED": "학습 작업을 완료하지 못했습니다. 기존 모델과 수신 원본은 유지합니다.",
}
ERRORS = {
    "TRAINING_UNAVAILABLE": "학습 모델 경로와 호환 환경을 먼저 설정하세요.",
    "TRAINING_BUSY": "다른 학습 작업이 진행 중입니다. 완료하거나 취소한 뒤 다시 시도하세요.",
    "JOB_CONFLICT": "같은 작업 식별자로 다른 학습 조건을 요청할 수 없습니다.",
    "INVALID_JOB_REQUEST": "작업 식별자·학습 대상 모델·버전 식별값과 1부터 20 사이의 정수 반복 횟수를 확인하세요.",
    "DATASET_TOO_SMALL": "서로 다른 원 요구사항의 짧은 일반 학습·검증 자료와 별도로 분리할 시험 그룹이 필요합니다.",
    "DATASET_INVALID": "수신 완료된 승인 자료의 계약과 무결성을 다시 확인하세요.",
    "DATASET_TOO_LARGE": "한 번에 준비할 수 있는 검증 자료의 크기 또는 파일 수를 초과했습니다.",
    "PREPARATION_FAILED": "학습 입력을 준비하지 못했습니다. 모델 호환성과 승인 자료를 확인하세요.",
    "TRAINING_FAILED": "시험 학습을 완료하지 못했습니다. 학습 환경과 자료를 확인하세요.",
    "VERIFICATION_FAILED": "어댑터 저장 또는 재로딩 검증을 완료하지 못했습니다.",
    "JOB_TIMEOUT": "학습 단계의 제한 시간을 초과해 작업을 중단했습니다.",
    "JOB_INTERRUPTED": "학습 서비스가 중단되어 이전 작업을 실패로 확정했습니다. 자동으로 다시 실행하지 않습니다.",
    "TRAINING_INTERNAL_ERROR": "학습 작업을 처리하지 못했습니다. 서버 상태를 확인하세요.",
    "NOT_FOUND": "학습 작업을 찾을 수 없습니다.",
    "DELIVERY_NOT_READY": "저장과 재로딩을 마친 학습 작업만 전달할 수 있습니다.",
    "DELIVERY_FAILED": "학습 어댑터의 무결성 검사 또는 공유 폴더 게시를 완료하지 못했습니다.",
}
logger = logging.getLogger("doenaai.training.jobs")


class TrainingJobError(ValueError):
    """외부에는 고정된 한국어 안내와 오류 코드만 전달한다."""

    def __init__(self, code, message=None, http_status=409):
        self.code = code if code in ERRORS else "TRAINING_INTERNAL_ERROR"
        self.message = ERRORS[self.code]
        self.http_status = http_status
        super().__init__(self.message)


def job_id(value):
    """경로로 해석할 수 없는 정규 UUID만 사용한다."""
    if not isinstance(value, str):
        raise TrainingJobError("INVALID_JOB_REQUEST", http_status=422)
    try:
        parsed = str(UUID(value))
    except ValueError:
        raise TrainingJobError("INVALID_JOB_REQUEST", http_status=422) from None
    if parsed != value:
        raise TrainingJobError("INVALID_JOB_REQUEST", http_status=422)
    return parsed


def now():
    """상태 시각을 같은 시간대로 기록한다."""
    return datetime.now(timezone.utc).isoformat()


class TrainingJobStore:
    """단일 작업 잠금과 영속 기록으로 중복 실행·재시작 뒤의 자동 재학습을 막는다."""

    def __init__(self, receiver, model_dir=None, *, state_dir=None, work_root=None, root=ROOT,
                 popen_factory=None, stage_timeout=3600, models_file=None):
        self.receiver = receiver
        self.root = Path(root)
        self.model_dir = Path(model_dir) if model_dir else None
        self.models_file = Path(models_file) if models_file else None
        self.state_dir = private_directory(state_dir or self.root / "datasets/training-jobs")
        self.work_root = private_directory(work_root or self.root / "outputs/web-jobs")
        self.popen_factory = popen_factory or subprocess.Popen
        self.stage_timeout = stage_timeout
        self.lock = threading.RLock()
        self.stopping = threading.Event()
        self.cancel_requested = threading.Event()
        self.worker = None
        self.process = None
        self.unsafe_process = False
        self.owner = None
        self.gpu_lease = GpuLease(self.state_dir / ".gpu.lock")
        self._claim_owner()

    def _claim_owner(self):
        """실행 자식도 잠금을 보유하므로 수신 서버만 죽어도 같은 GPU 작업을 겹치지 않는다."""
        with self.lock:
            if self.owner is not None or self.stopping.is_set():
                return self.owner is not None
            descriptor = os.open(self.state_dir / ".worker.lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
            try:
                fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                os.close(descriptor)
                return False
            self.owner = descriptor
            for path in self.state_dir.glob("*.json"):
                record = self._read(path)
                if record and record["status"] in ACTIVE:
                    self._set(record, "FAILED", record["progress"], "JOB_INTERRUPTED")
            return True

    def _configuration_error(self, source_model=None):
        """설정한 로컬 모델만 확인하며 모델 다운로드와 GPU 연산은 하지 않는다."""
        try:
            binding = self._binding(source_model)
        except (RegistryError, OSError, ValueError, TypeError):
            return "선택한 분석 모델에 대응하는 로컬 MLX 모델 등록과 다운로드 명세를 확인해 주세요."
        if not self._supported_target(binding.target):
            return "등록한 모델의 학습기가 지원되지 않습니다. 공통 MLX LoRA 학습기와 모델별 프로필을 확인하세요."
        model = binding.directory
        if not model.is_absolute() or model.is_symlink() or not model.is_dir():
            return "학습 모델 경로는 심볼릭 링크가 아닌 로컬 폴더의 절대 경로여야 합니다."
        try:
            for name in ("config.json", "download_manifest.json"):
                path = model / name
                if path.is_symlink() or not path.is_file() or path.stat().st_size > 128 * 1024:
                    return "고정 모델의 설정과 다운로드 명세를 확인할 수 없습니다."
            config = json.loads((model / "config.json").read_text())
            manifest = json.loads((model / "download_manifest.json").read_text())
            text = config.get("text_config", config)
            profile = binding.profile
            if config.get("model_type") != profile["modelType"]:
                return "등록한 학습 프로필과 실제 MLX 모델 구조가 다릅니다."
            if (binding.target["trainer"] == QWEN_TRAINER and text.get("tie_word_embeddings") is not False
                    or not isinstance(manifest.get("revision"), str) or not re.fullmatch(r"[0-9a-f]{40}", manifest["revision"])):
                return "학습기의 모델 구조와 고정된 다운로드 버전을 확인하세요."
            layers = text.get("num_hidden_layers", text.get("num_layers"))
            if type(layers) is int and profile["loraLayers"] > layers:
                return "학습할 층 수가 기반 모델의 전체 층 수를 넘습니다."
            weights = list(model.glob("*.safetensors"))
            if not weights or any(path.is_symlink() or not path.is_file() for path in weights):
                return "학습에 사용할 로컬 가중치 파일을 확인할 수 없습니다."
            if sys.platform != "darwin" or any(importlib.util.find_spec(name) is None for name in ("mlx", "mlx_lm", "transformers")):
                return "맥북 학습 서버의 MLX·MLX-LM·Transformers 환경을 준비하세요."
        except (OSError, ValueError, TypeError, AttributeError, ImportError, KeyError):
            return "학습 모델 설정과 실행 환경을 확인하지 못했습니다."
        return None

    @staticmethod
    def _supported_target(target):
        """기존 Qwen 시험기와 등록한 공통 학습기만 실행한다."""
        return isinstance(target, dict) and (target.get("trainer") == GENERIC_TRAINER or (
            target.get("trainer") == QWEN_TRAINER and target.get("baseModel") == QWEN_BASE
            and target.get("sourceModel") == QWEN_SOURCE))

    @staticmethod
    def _selected(target):
        """실제 선택한 파생 모델과 학습 자료의 원래 생성 모델을 구분한다."""
        return target.get("selectedModel", target.get("sourceModel"))

    @staticmethod
    def _base_preserved(training, directory):
        """부모 학습 당시와 같은 기반 파일 상태인지 변환과 이어 학습에서 함께 확인한다."""
        protected_files = training.get("protectedFiles")
        protected = protected_files.get("model") if isinstance(protected_files, dict) else None
        current = {path.name: [path.stat().st_size, path.stat().st_mtime_ns]
                   for path in directory.iterdir() if path.is_file()}
        return (isinstance(protected, dict) and bool(protected) and current == protected
                and not any(path.is_symlink() for path in directory.iterdir()))

    def _parent_source(self, identifier):
        """완료된 부모 작업의 설정과 가중치를 검증하고 원본 경로만 반환한다."""
        record = self._read(self._path(identifier))
        if record is None or record["status"] != "SUCCEEDED" or not self._supported_target(record.get("target")):
            raise RegistryError("이어 학습할 완료 작업을 확인하지 못했습니다.")
        adapter = self.root / "adapters" / ("trial-web-" + identifier)
        reports = self.work_root / identifier / "dataset"
        if adapter.is_symlink() or adapter.parent.is_symlink() or reports.is_symlink() or reports.parent.is_symlink():
            raise RegistryError("이전 학습 산출물의 경로를 확인하세요.")
        config, identity, _ = execution_summary(adapter, reports)
        target = record["target"]
        if identity != {"modelId": target["baseModel"], "revision": target["revision"]}:
            raise RegistryError("이전 어댑터와 기반 모델의 버전이 다릅니다.")
        return record, adapter, config, adapter_checksum(adapter / "adapters.safetensors")[0], adapter_checksum(adapter / "adapter_config.json")[0]

    def _binding(self, source_model=None, _visited=()):
        """원본 모델은 등록 설정으로, 파생 모델은 검증된 부모 어댑터로 연결한다."""
        selected = source_model if source_model is not None else QWEN_SOURCE
        if selected in _visited or len(_visited) >= 16:
            raise RegistryError("학습 모델의 부모 연결이 순환하거나 너무 깁니다.")
        visited = (*_visited, selected)
        derived = DERIVED_MODEL.fullmatch(selected) if isinstance(selected, str) else None
        if derived:
            parent_id = derived.group(1)
            parent, adapter, config, weights_hash, config_hash = self._parent_source(parent_id)
            binding = self._binding(self._selected(parent["target"]), visited)
            if binding.target != parent["target"]:
                raise RegistryError("이전 학습 이후 기반 모델 대응이나 부모 어댑터가 변경됐습니다.")
        else:
            binding = resolve(selected, self.model_dir, self.models_file)
            parent_id = binding.resume_job_id
            if not parent_id:
                return binding
            parent, adapter, config, weights_hash, config_hash = self._parent_source(parent_id)
            parent_binding = self._binding(self._selected(parent["target"]), visited)
            if parent_binding.target != parent["target"] or any(
                    binding.target[key] != parent["target"].get(key) for key in ("baseModel", "revision", "trainer")):
                raise RegistryError("등록한 이어 학습 대상과 이전 작업의 기반 모델이 다릅니다.")
        profile = binding.profile
        expected_lora = {"rank": profile["loraRank"], "scale": profile["loraScale"],
                         "dropout": profile["loraDropout"], "keys": profile["loraKeys"]}
        if config.get("num_layers") != profile["loraLayers"] or config.get("lora_parameters") != expected_lora:
            raise RegistryError("이전 어댑터와 학습 프로필의 대상 모듈 또는 크기가 다릅니다.")
        training = delivery_json(self.work_root / parent_id / "dataset/training-report.json")
        if (not self._base_preserved(training, binding.directory)
                or (parent["target"]["trainer"] == GENERIC_TRAINER and training.get("profileSha256") != digest(profile))):
            raise RegistryError("부모 학습 이후 기반 파일이나 학습 프로필이 변경됐습니다.")
        target = {key: parent["target"][key] for key in ("sourceModel", "baseModel", "revision", "trainer")}
        target.update(selectedModel=selected, resumeJobId=parent_id, parentAdapterSha256=weights_hash,
                      parentConfigSha256=config_hash)
        target["digest"] = digest({**target, "directory": str(binding.directory), "profile": profile})
        return replace(binding, target=target, resume_job_id=parent_id)

    def _resume_adapter(self, binding):
        """부모 어댑터는 작업별 새 폴더에 결과를 쓸 때만 읽기 전용으로 전달한다."""
        if not binding.resume_job_id:
            return None
        _, path, _, weights_hash, config_hash = self._parent_source(binding.resume_job_id)
        if (weights_hash != binding.target.get("parentAdapterSha256")
                or config_hash != binding.target.get("parentConfigSha256")):
            raise RegistryError("접수한 뒤 부모 어댑터가 변경됐습니다.")
        return path

    @staticmethod
    def _public_profile(binding):
        """브라우저에는 학습 조건과 출처 식별자만 제공한다."""
        profile = binding.profile
        result = {key: profile[key] for key in ("modelType", "loraLayers", "loraRank", "maxSequenceLength", "batchSize")}
        result.update(trainer=binding.target["trainer"], continuation=binding.resume_job_id is not None)
        if binding.resume_job_id:
            result["parentJobId"] = binding.resume_job_id
        return result

    def training_target(self, source_model=None):
        """등록한 기반 모델과 고정 버전을 반환하고 확인 실패 때는 대체 모델을 쓰지 않는다."""
        try:
            return self._binding(source_model).target
        except (RegistryError, OSError, ValueError, TypeError, AttributeError, KeyError):
            return None

    def dataset_catalog(self, source_model=None):
        """같은 생성 모델의 검증 자료만 표시하고 원문과 다른 모델의 사례는 노출하지 않는다."""
        target = self.training_target(source_model)
        samples = []
        excluded = 0
        total_bytes = 0
        file_count = 0
        with self.receiver.lock:
            for path in sorted(self.receiver.state_dir.glob("*.json")):
                if not HASH.fullmatch(path.stem):
                    continue
                receipt = self.receiver.status(path.stem)
                if not receipt or receipt.get("status") != "VERIFIED":
                    continue
                source = self.receiver.inbox / f"{path.stem}.jsonl"
                if source.is_symlink() or not source.is_file() or content_hash(source) != (path.stem, receipt.get("size")):
                    raise TrainingJobError("DATASET_INVALID")
                total_bytes += source.stat().st_size
                file_count += 1
                if total_bytes > 32 * 1024 * 1024 or file_count > 100:
                    raise TrainingJobError("DATASET_TOO_LARGE")
                values = read_jsonl(source)
                if len(values) != receipt.get("cases"):
                    raise TrainingJobError("DATASET_INVALID")
                for value in values:
                    try:
                        validate_case(value)
                    except DatasetError:
                        raise TrainingJobError("DATASET_INVALID") from None
                    if target and value.get("sourceModel") == target["sourceModel"]:
                        samples.append({"caseId": value["caseId"], "sourceModel": value["sourceModel"]})
                    else:
                        excluded += 1
        return {"target": target, "samples": samples[:100], "total": len(samples), "excluded": excluded,
                "truncated": len(samples) > 100}

    def capabilities(self, include_dataset=True, source_model=None):
        """실행 기능 제공 여부와 불가 이유만 반환하고 전체 경로는 공개하지 않는다."""
        with self.lock:
            configured = self._configuration_error(source_model)
            if not self.training_target(source_model):
                configured = configured or "학습 기반 모델과 분석 생성 모델의 대응 정보를 확인할 수 없습니다."
            owner = self._claim_owner()
            reason = configured or ("이전 작업 프로세스의 종료를 확인하지 못했습니다." if self.unsafe_process else None) or (
                None if owner else "다른 학습 서버 프로세스가 작업 잠금을 보유하고 있습니다.")
            target = self.training_target(source_model)
            reason_code = "MODEL_NOT_REGISTERED" if target is None else (
                "TRAINER_UNSUPPORTED" if not self._supported_target(target)
                else "TRAINING_UNAVAILABLE" if reason else "READY")
            try:
                binding = self._binding(source_model)
                profile = self._public_profile(binding)
                model_export = export_capability(binding.profile, base_model=target["baseModel"], revision=target["revision"])
            except (RegistryError, OSError, ValueError, TypeError, KeyError, AttributeError):
                profile = None
                model_export = {"supported": False, "reasonCode": "MODEL_NOT_REGISTERED", "message": "학습 모델 등록을 먼저 확인하세요."}
            return {"trainingExecution": reason is None and not self.stopping.is_set(),
                    "trainingExecutionReason": reason or "일반 분석의 제한된 시험 학습만 실행하며 운영 모델은 바꾸지 않습니다.",
                    "dataset": self.dataset_catalog(source_model) if include_dataset else {},
                    "requestedModel": source_model or QWEN_SOURCE, "trainingReasonCode": reason_code,
                    "trainingProfile": profile, "modelExport": model_export}

    def _path(self, identifier):
        return self.state_dir / f"{job_id(identifier)}.json"

    def _read(self, path):
        if path.is_symlink() or not path.is_file():
            return None
        try:
            if path.stat().st_size > 32768:
                raise ValueError()
            value = json.loads(path.read_text())
            if (not isinstance(value, dict) or job_id(value.get("id")) != path.stem
                    or value.get("status") not in ACTIVE | TERMINAL
                    or type(value.get("iterations")) is not int or not 1 <= value["iterations"] <= 20
                    or type(value.get("progress")) is not int or not 0 <= value["progress"] <= 100):
                raise ValueError()
            for key in ("createdAt", "updatedAt"):
                value[key] = datetime.fromisoformat(value[key]).isoformat()
            return value
        except (OSError, ValueError, TypeError, KeyError):
            raise TrainingJobError("TRAINING_INTERNAL_ERROR", http_status=503) from None

    def _public(self, record):
        result = {key: record[key] for key in ("id", "status", "progress", "iterations", "createdAt", "updatedAt")}
        if isinstance(record.get("target"), dict):
            result["target"] = record["target"]
        code = record.get("errorCode")
        if code in ERRORS:
            result["errorCode"] = code
        result["message"] = ERRORS[code] if code in ERRORS else MESSAGES[record["status"]]
        if isinstance(record.get("metrics"), dict):
            allowed = {"trainingSamples", "validationSamples", "testSamples", "finalTrainingLoss", "validationLossBefore",
                       "validationLossAfter", "adapterTensors", "peakGpuGiB", "reloadMaxLogitDifference", "excludedTestSamples"}
            result["metrics"] = {key: value for key, value in record["metrics"].items()
                                 if key in allowed and type(value) in (int, float) and math.isfinite(value)}
            result["metrics"]["qualityEvaluated"] = False
            result["metrics"]["independentTestExecuted"] = False
            if record["status"] == "SUCCEEDED" and result["metrics"].get("excludedTestSamples", 0) > 0:
                result["message"] += " 길이 상한을 넘은 시험 자료는 자르지 않고 제외했습니다."
        return result

    def get_job(self, identifier):
        with self.lock:
            self._claim_owner()
            record = self._read(self._path(identifier))
            return self._public(record) if record else None

    def list_jobs(self):
        with self.lock:
            self._claim_owner()
            records = [value for path in self.state_dir.glob("*.json") if (value := self._read(path))]
            records.sort(key=lambda item: (item["createdAt"], item["id"]), reverse=True)
            return {"jobs": [self._public(value) for value in records[:100]], "limit": 100, "truncated": len(records) > 100}

    def model_build_source(self, identifier):
        """완료 학습의 원본·기반 버전을 다시 확인해 서버 내부 생성기에만 전달한다."""
        identifier = job_id(identifier)
        with self.lock:
            record = self._read(self._path(identifier))
            if record is None:
                raise TrainingJobError("NOT_FOUND", http_status=404)
            target = record.get("target")
            if record["status"] != "SUCCEEDED" or not self._supported_target(target):
                raise TrainingJobError("DELIVERY_NOT_READY")
            binding = self._binding(self._selected(target))
            if target != binding.target:
                raise TrainingJobError("DELIVERY_NOT_READY")
            adapter = self.root / "adapters" / ("trial-web-" + identifier)
            reports = self.work_root / identifier / "dataset"
            config, identity, summary = execution_summary(adapter, reports)
            training = delivery_json(reports / "training-report.json")
            if (identity != {"modelId": target["baseModel"], "revision": target.get("revision")}
                    or not self._base_preserved(training, binding.directory)):
                raise TrainingJobError("DELIVERY_NOT_READY")
            if target.get("trainer") == GENERIC_TRAINER and training.get("profileSha256") != digest(binding.profile):
                raise TrainingJobError("DELIVERY_NOT_READY")
            parent = self._resume_adapter(binding)
            if parent is not None and any(training.get(key) != target.get(key)
                                          for key in ("parentAdapterSha256", "parentConfigSha256")):
                raise TrainingJobError("DELIVERY_NOT_READY")
            return {"target": dict(target), "model_dir": binding.directory, "adapter": adapter,
                    "reports": reports, "summary": summary, "adapterSha256": training["adapterSha256"],
                    "profile": binding.profile, "parentAdapter": parent}

    def delivery(self, identifier, secret):
        """완료 작업의 고정 산출물만 검증해 공유 폴더에 게시하며 학습은 실행하지 않는다."""
        identifier = job_id(identifier)
        with self.lock:
            record = self._read(self._path(identifier))
            if record is None:
                raise TrainingJobError("NOT_FOUND", http_status=404)
            target = record.get("target")
            if record["status"] != "SUCCEEDED" or not self._supported_target(target):
                raise TrainingJobError("DELIVERY_NOT_READY")
            if target["trainer"] == GENERIC_TRAINER or target.get("resumeJobId"):
                self.model_build_source(identifier)
            version = "web-" + identifier
            adapter = self.root / "adapters" / ("trial-web-" + identifier)
            reports = self.work_root / identifier / "dataset"
            output = self.root / "outputs/model-packages" / version
            try:
                config, identity, summary = execution_summary(adapter, reports)
                if identity != {"modelId": target["baseModel"], "revision": target.get("revision")}:
                    raise PackageError("학습 작업과 산출물의 기반 모델 버전이 다릅니다.")
                if output.exists() or output.is_symlink():
                    verified = verify_package(output, secret)
                    manifest = delivery_json(output / "manifest.json")
                    training = delivery_json(reports / "training-report.json")
                    entries = {item["path"]: item for item in manifest["files"]}
                    if (manifest["version"] != version or manifest["model"] != identity
                            or entries["adapters.safetensors"]["sha256"] != training.get("adapterSha256")
                            or delivery_json(output / "adapter_config.json") != config
                            or delivery_json(output / "evaluation-summary.json") != summary):
                        raise PackageError("기존 전달 패키지가 완료 작업과 다릅니다.")
                else:
                    verified = create_package(adapter, reports, output, version, secret)
                publish_package(output, self.receiver.shared_dir, secret)
                return {"status": "PUBLISHED", "jobId": identifier, "version": version,
                        "manifestSha256": verified["manifestSha256"], "sourceModel": target["sourceModel"],
                        "baseModel": identity["modelId"], "baseRevision": identity["revision"]}
            except (OSError, ValueError, TypeError, KeyError):
                raise TrainingJobError("DELIVERY_FAILED", http_status=409) from None

    def _set(self, record, status, progress, error=None, metrics=None):
        record.update(status=status, progress=progress, updatedAt=now())
        if error:
            record["errorCode"] = error
        if metrics is not None:
            record["metrics"] = metrics
        write_json_atomic(self._path(record["id"]), record)
        logger.info("학습 작업 상태 변경 status=%s code=%s", status, error or "NONE")

    def submit(self, body):
        if (not isinstance(body, dict) or set(body) != {"jobId", "iterations", "model", "digest"}
                or type(body.get("iterations")) is not int or not 1 <= body["iterations"] <= 20):
            raise TrainingJobError("INVALID_JOB_REQUEST", http_status=422)
        identifier = job_id(body["jobId"])
        with self.lock:
            previous = self._read(self._path(identifier))
            if previous:
                if (previous["iterations"] != body["iterations"] or not previous.get("target")
                        or self._selected(previous["target"]) != body["model"] or previous["target"]["digest"] != body["digest"]):
                    raise TrainingJobError("JOB_CONFLICT")
                return self._public(previous)
            target = self.training_target(body["model"])
            if not target or body["model"] != self._selected(target) or body["digest"] != target["digest"]:
                raise TrainingJobError("TRAINING_UNAVAILABLE")
            if not self.capabilities(include_dataset=False, source_model=body["model"])["trainingExecution"]:
                raise TrainingJobError("TRAINING_UNAVAILABLE")
            if self.worker and self.worker.is_alive():
                raise TrainingJobError("TRAINING_BUSY")
            for path in self.state_dir.glob("*.json"):
                record = self._read(path)
                if record and record["status"] in ACTIVE:
                    raise TrainingJobError("TRAINING_BUSY")
            if not self.gpu_lease.acquire():
                raise TrainingJobError("TRAINING_BUSY")
            created = now()
            record = {"id": identifier, "status": "PENDING", "progress": 0, "iterations": body["iterations"],
                      "createdAt": created, "updatedAt": created, "target": target}
            try:
                write_json_atomic(self._path(identifier), record)
                self.cancel_requested.clear()
                self.worker = threading.Thread(target=self._worker_entry, args=(identifier,), name="training-job", daemon=True)
                self.worker.start()
            except Exception:
                self.gpu_lease.release()
                self._set(record, "FAILED", 0, "TRAINING_INTERNAL_ERROR")
                raise TrainingJobError("TRAINING_INTERNAL_ERROR", http_status=503) from None
            return self._public(record)

    def cancel(self, identifier, body):
        if body != {}:
            raise TrainingJobError("INVALID_JOB_REQUEST", http_status=422)
        with self.lock:
            self._claim_owner()
            record = self._read(self._path(identifier))
            if not record:
                raise TrainingJobError("NOT_FOUND", http_status=404)
            if record["status"] in ACTIVE:
                if self.owner is None:
                    raise TrainingJobError("TRAINING_BUSY")
                self.cancel_requested.set()
            return self._public(record)

    def _check_cancel(self):
        if self.stopping.is_set() or self.cancel_requested.is_set():
            raise InterruptedError()

    def _snapshot(self, directory, source_model=None):
        """수신 완료 기록과 이름·크기·해시를 다시 대조한 사본만 새 작업 폴더에 둔다."""
        sources = private_directory(directory / "incoming")
        records, total, count = [], 0, 0
        with self.receiver.lock:
            for receipt_path in sorted(self.receiver.state_dir.glob("*.json")):
                self._check_cancel()
                checksum = receipt_path.stem
                if not HASH.fullmatch(checksum):
                    continue
                receipt = self.receiver.status(checksum)
                if not receipt or receipt.get("status") != "VERIFIED":
                    continue
                source = self.receiver.inbox / f"{checksum}.jsonl"
                if source.is_symlink() or not source.is_file():
                    raise TrainingJobError("DATASET_INVALID")
                total += source.stat().st_size
                count += 1
                if total > 32 * 1024 * 1024 or count > 100:
                    raise TrainingJobError("DATASET_TOO_LARGE")
                if content_hash(source) != (checksum, receipt.get("size")):
                    raise TrainingJobError("DATASET_INVALID")
                values = read_jsonl(source)
                if len(values) != receipt.get("cases"):
                    raise TrainingJobError("DATASET_INVALID")
                for value in values:
                    validate_case(value)
                target = self.training_target(source_model)
                matched = [value for value in values if target and value.get("sourceModel") == target["sourceModel"]]
                if not matched:
                    continue
                destination = sources / source.name
                # 혼합 파일도 해당 모델의 사례만 새 사본에 쓰며 수신 원본은 보존한다.
                with destination.open("x") as copied:
                    os.fchmod(copied.fileno(), 0o600)
                    for value in matched:
                        copied.write(json.dumps(value, ensure_ascii=False) + "\n")
                records.extend(matched)
        groups = {}
        for record in records:
            requirement = record["request"]["sourceSummary"]["changeRequirement"].get("requirementId")
            if not isinstance(requirement, str) or not requirement.strip():
                raise TrainingJobError("DATASET_INVALID")
            identity = digest([record["projectGroup"], requirement])
            groups.setdefault(identity, []).append(record["caseId"])
        if len(groups) < 3:
            raise TrainingJobError("DATASET_TOO_SMALL")
        return sources

    def _stop_process(self, process):
        """이 작업에서 시작한 별도 프로세스 묶음에만 종료 신호를 보낸다."""
        if process.poll() is not None:
            return
        try:
            os.killpg(process.pid, signal.SIGTERM)
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=5)
        except ProcessLookupError:
            pass
        except OSError:
            self.unsafe_process = True
            raise TrainingJobError("TRAINING_INTERNAL_ERROR") from None
        finally:
            if process.poll() is None:
                self.unsafe_process = True

    def _execute(self, arguments, failure, failure_result=None):
        """셸과 원격 주소 없이 고정된 Python 도구만 실행하고 원문 출력은 폐기한다."""
        self._check_cancel()
        environment = {key: value for key, value in os.environ.items() if key in {"PATH", "HOME", "TMPDIR", "LANG", "LC_ALL"}}
        environment.update(HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1", HF_DATASETS_OFFLINE="1",
                           HF_HUB_DISABLE_TELEMETRY="1", HF_HOME=str(self.root / ".cache/huggingface"), PYTHONUNBUFFERED="1")
        process = self.popen_factory([sys.executable, *map(str, arguments)], cwd=self.root, env=environment,
                                     stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                     shell=False, start_new_session=True,
                                     pass_fds=tuple(value for value in (self.owner, self.gpu_lease.descriptor) if value is not None))
        self.process = process
        started = time.monotonic()
        try:
            while process.poll() is None:
                self._check_cancel()
                if time.monotonic() - started > self.stage_timeout:
                    raise TrainingJobError("JOB_TIMEOUT")
                self.cancel_requested.wait(0.1)
            self._check_cancel()
            if process.returncode != 0:
                if failure_result and failure_result.is_file() and not failure_result.is_symlink() and failure_result.stat().st_size < 1024:
                    try:
                        code = json.loads(failure_result.read_text()).get("code")
                        if code in {"DATASET_TOO_SMALL", "DATASET_INVALID"}:
                            raise TrainingJobError(code)
                    except (ValueError, TypeError, AttributeError) as error:
                        if isinstance(error, TrainingJobError):
                            raise
                raise TrainingJobError(failure)
        finally:
            self._stop_process(process)
            self.process = None

    def _phase(self, identifier, status, progress):
        with self.lock:
            self._check_cancel()
            record = self._read(self._path(identifier))
            self._set(record, status, progress)

    def _worker_entry(self, identifier):
        """디스크 오류가 겹쳐도 워커의 원문 예외와 전체 경로를 로그에 남기지 않는다."""
        try:
            self._run(identifier)
        except Exception:
            logger.error("학습 작업 상태 저장 실패 code=TRAINING_STATE_UNAVAILABLE")

    def _run(self, identifier):
        try:
            record = self._read(self._path(identifier))
            source_model = self._selected(record.get("target", {}))
            if not source_model:
                raise TrainingJobError("TRAINING_UNAVAILABLE")
            binding = self._binding(source_model)
            if record["target"] != binding.target or self._configuration_error(source_model):
                raise TrainingJobError("TRAINING_UNAVAILABLE")
            model_dir = binding.directory
            pinned_target = record["target"]
            profile = binding.profile
            parent_adapter = self._resume_adapter(binding)

            def run_stage(arguments, code, result_path=None):
                # 등록 파일이 실행 중 바뀌어도 다른 기반 모델로 다음 단계를 실행하지 않는다.
                if self.training_target(source_model) != pinned_target:
                    raise TrainingJobError("TRAINING_UNAVAILABLE")
                self._execute(arguments, code, result_path)

            self._phase(identifier, "PREPARING", 10)
            directory = self.work_root / identifier
            directory.mkdir(mode=0o700, exist_ok=False)
            sources = self._snapshot(directory, source_model)
            dataset = directory / "dataset"
            adapter = self.root / "adapters" / f"trial-web-{identifier}"
            profile_file = directory / "training-profile.json"
            write_json_atomic(profile_file, profile)
            run_stage([self.root / "training_job_prepare.py", "--input-dir", sources, "--output", dataset,
                           "--model", model_dir, "--max-tokens", profile["maxSequenceLength"]],
                      "PREPARATION_FAILED", directory / "preparation-result.json")
            manifest = json.loads((dataset / "manifest.json").read_text())
            counts = manifest.get("stageSampleCounts", {})
            if any(type(counts.get(split)) is not int or counts[split] < 1 for split in ("train", "valid")):
                raise TrainingJobError("DATASET_TOO_SMALL")
            self._phase(identifier, "TRAINING", 30)
            record = self._read(self._path(identifier))
            generic = pinned_target["trainer"] == GENERIC_TRAINER
            script = self.root / ("mlx_lora_train.py" if generic else "trial_train.py")
            common = ["--dataset", dataset, "--adapter", adapter, "--model", model_dir]
            if generic:
                common.extend(["--profile-file", profile_file])
            train_args = [script, "train", *common, "--iters", record["iterations"]]
            if not generic:
                train_args.extend(["--layers", "1"])
            if parent_adapter is not None:
                train_args.extend(["--resume-adapter", parent_adapter])
            run_stage(train_args, "TRAINING_FAILED")
            self._phase(identifier, "VERIFYING", 85)
            run_stage([script, "reload", *common], "VERIFICATION_FAILED")
            report = json.loads((dataset / "training-report.json").read_text())
            reload = json.loads((dataset / "reload-report.json").read_text())
            if report.get("status") != "complete" or reload.get("status") != "complete" or report.get("protectedFilesUnchanged") is not True:
                raise TrainingJobError("VERIFICATION_FAILED")
            if generic and report.get("profileSha256") != digest(profile):
                raise TrainingJobError("VERIFICATION_FAILED")
            if parent_adapter is not None and any(report.get(key) != pinned_target.get(key)
                                                  for key in ("parentAdapterSha256", "parentConfigSha256")):
                raise TrainingJobError("VERIFICATION_FAILED")
            metrics = {"trainingSamples": counts["train"], "validationSamples": counts["valid"], "testSamples": counts["test"],
                       "finalTrainingLoss": report["steps"][-1]["loss"], "validationLossBefore": report["validationLossBefore"]["valid"],
                       "validationLossAfter": report["validationLossAfter"]["valid"], "adapterTensors": report["adapterTensors"],
                       "peakGpuGiB": report["peakGpuGiB"], "reloadMaxLogitDifference": reload["maxLogitDifference"],
                       "excludedTestSamples": sum(1 for item in manifest.get("details", []) if item.get("split") == "test" and item.get("included") is False)}
            if (any(type(value) not in (int, float) or not math.isfinite(value) for value in metrics.values())
                    or metrics["adapterTensors"] < 1 or metrics["reloadMaxLogitDifference"] > 0.02):
                raise TrainingJobError("VERIFICATION_FAILED")
            with self.lock:
                self._check_cancel()
                self._set(self._read(self._path(identifier)), "SUCCEEDED", 100, metrics=metrics)
        except InterruptedError:
            with self.lock:
                record = self._read(self._path(identifier))
                self._set(record, "CANCELLED", record["progress"])
        except Exception as error:
            code = error.code if isinstance(error, TrainingJobError) else (
                "DATASET_INVALID" if isinstance(error, (DatasetError, TransferError)) else "TRAINING_INTERNAL_ERROR")
            with self.lock:
                record = self._read(self._path(identifier))
                self._set(record, "FAILED", record["progress"], code)

        finally:
            if not self.unsafe_process:
                self.gpu_lease.release()

    def close(self):
        """서비스 종료 시 이 워커만 취소하고 상태와 새 산출물은 보존한다."""
        self.stopping.set()
        self.cancel_requested.set()
        if self.worker:
            self.worker.join(timeout=12)
        with self.lock:
            if self.owner is not None and (not self.worker or not self.worker.is_alive()):
                os.close(self.owner)
                self.owner = None
                if not self.unsafe_process:
                    self.gpu_lease.release()
