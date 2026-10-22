"""서명된 완성 모델을 기존 추론 엔진에 등록하고 설치 파일의 출처를 보관한다."""

from __future__ import annotations

import asyncio
import fcntl
import hashlib
import json
import os
import re
import shutil
import sys
import tempfile
import threading
import time
from pathlib import Path
from uuid import UUID

import httpx

from app.services.cloud_model_files import CloudModelFilesError, ensure_local_files

TRAINING_ROOT = Path(__file__).resolve().parents[3] / "training"
if str(TRAINING_ROOT) not in sys.path:
    sys.path.insert(0, str(TRAINING_ROOT))

from model_delivery import PackageError, canonical, directory, read_json  # noqa: E402
from model_registry import QWEN_BASE, QWEN_SOURCE  # noqa: E402

MODEL = re.compile(r"doenaai-trained:web-([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})\Z")
HASH = re.compile(r"[0-9a-f]{64}\Z")
METADATA = {"jobId", "version", "manifestSha256", "sourceModel", "baseModel", "baseRevision", "model",
            "artifactSha256", "artifactSize", "quantization"}
MAX_WEIGHTS = 64 * 1024 ** 3
IMPORT_PHASE_MESSAGES = {
    "DOWNLOADING": "공유 저장소에 모델 파일 다운로드를 요청했습니다. AI 분석/추론 서버에 파일이 준비되기를 기다립니다.",
    "VERIFYING": "AI 분석/추론 서버에서 모델 파일의 서명·크기·해시를 검사하고 있습니다.",
    "COPYING": "검사한 모델 파일을 AI 분석/추론 서버에 보관하고 보관한 파일을 다시 확인하고 있습니다.",
    "REGISTERING": "AI 분석/추론 서버에서 기존 모델 실행 환경에 새 모델을 등록하고 있습니다.",
}


class TrainedModelError(Exception):
    """로컬 경로와 엔진 원문을 제외한 모델 등록 오류다."""

    def __init__(self, status, code, message):
        super().__init__(message)
        self.status, self.code, self.message = status, code, message


def metadata_input(value):
    """완료 학습과 고정된 모델 이름만 등록 대상으로 받는다."""
    try:
        if not isinstance(value, dict) or set(value) != METADATA:
            raise ValueError()
        identifier = str(UUID(value["jobId"]))
        if (identifier != value["jobId"] or value["version"] != "trained-web-" + identifier
                or value["model"] != "doenaai-trained:web-" + identifier
                or value["sourceModel"] != QWEN_SOURCE or value["baseModel"] != QWEN_BASE
                or not isinstance(value["baseRevision"], str) or not re.fullmatch(r"[0-9a-f]{40}", value["baseRevision"])
                or any(not isinstance(value[key], str) or not HASH.fullmatch(value[key]) for key in ("manifestSha256", "artifactSha256"))
                or type(value["artifactSize"]) is not int or not 24 <= value["artifactSize"] <= MAX_WEIGHTS
                or value["quantization"] != "Q4_K_M"):
            raise ValueError()
        return dict(value)
    except (ValueError, TypeError, KeyError, AttributeError):
        raise TrainedModelError(422, "TRAINED_MODEL_REQUEST_INVALID", "학습 작업과 완성 모델의 식별값·파일 크기를 확인하세요.") from None


class OllamaModelImporter:
    """고정 엔진 주소에 파일을 나눠 보내고 새 모델을 생성한다. 추론은 실행하지 않는다."""

    def __init__(self, settings):
        self.base = str(settings.ollama_url).rstrip("/")

    @staticmethod
    def _json(client, method, path, body=None):
        with client.stream(method, path, json=body) as response:
            response.raise_for_status()
            data = bytearray()
            for chunk in response.iter_bytes():
                data.extend(chunk)
                if len(data) > 2 * 1024 ** 2:
                    raise ValueError()
            value = json.loads(data)
            if not isinstance(value, dict) or "error" in value:
                raise ValueError()
            return value

    def models(self, client):
        value = self._json(client, "GET", "/api/tags")
        if (not isinstance(value.get("models"), list) or len(value["models"]) > 500
                or any(not isinstance(row, dict) for row in value["models"])):
            raise ValueError()
        return value["models"]

    @staticmethod
    def _compatible(show):
        return (isinstance(show, dict) and isinstance(show.get("capabilities"), list) and "completion" in show["capabilities"]
                and not show.get("remote_host") and not show.get("remote_model")
                and isinstance(show.get("model_info"), dict) and show["model_info"].get("general.architecture") == "qwen35")

    def register(self, metadata, weights, deadline, *, check=None):
        """같은 이름의 외부 모델은 덮어쓰지 않고, 확인된 재시도만 이어간다."""
        with httpx.Client(base_url=self.base, timeout=httpx.Timeout(600, connect=10), follow_redirects=False) as client:
            if check:
                check()
            models = self.models(client)
            existing = next((row for row in models if row.get("name") == metadata["model"]), None)
            source = self._json(client, "POST", "/api/show", {"model": metadata["sourceModel"]})
            if not self._compatible(source):
                raise TrainedModelError(409, "TRAINED_MODEL_SOURCE_UNAVAILABLE", "학습 자료의 기반 분석 모델이 로컬에 설치돼 있어야 합니다.")
            template = source.get("template")
            if not isinstance(template, str) or not template.strip() or len(template) > 256 * 1024:
                raise TrainedModelError(409, "TRAINED_MODEL_TEMPLATE_INVALID", "기반 모델의 대화 형식을 확인하지 못했습니다.")
            body = {"model": metadata["model"], "files": {"model.gguf": "sha256:" + metadata["artifactSha256"]},
                    "template": template, "stream": False}
            for key in ("parser", "renderer"):
                value = source.get(key)
                if value is not None:
                    if not isinstance(value, str) or not re.fullmatch(r"[a-zA-Z0-9_.-]{1,100}", value):
                        raise ValueError()
                    body[key] = value
            if existing:
                actual = str(existing.get("digest", "")).removeprefix("sha256:")
                installed = self._json(client, "POST", "/api/show", {"model": metadata["model"]})
                modelfile = installed.get("modelfile", "")
                from_lines = [line.strip() for line in modelfile.splitlines() if line.strip().startswith("FROM ")] if isinstance(modelfile, str) else []
                expected_blob = "sha256-" + metadata["artifactSha256"]
                same_weights = len(from_lines) == 1 and from_lines[0].removeprefix("FROM ").strip('"').split("/")[-1] == expected_blob
                same_format = installed.get("template") == template and all(installed.get(key) == source.get(key) for key in ("parser", "renderer"))
                if same_weights and same_format and HASH.fullmatch(actual) and self._compatible(installed):
                    # 응답 유실 뒤 같은 가중치와 대화 형식의 등록만 멱등 재시도로 인정한다.
                    return actual
                raise TrainedModelError(409, "TRAINED_MODEL_NAME_CONFLICT", "같은 이름의 다른 설치 모델이 있습니다. 기존 모델은 덮어쓰지 않았습니다.")
            digest_path = "/api/blobs/sha256:" + metadata["artifactSha256"]
            with client.stream("HEAD", digest_path) as response:
                if response.status_code not in (200, 404):
                    response.raise_for_status()
                present = response.status_code == 200
            if not present:
                def chunks():
                    with weights.open("rb") as stream:
                        for chunk in iter(lambda: stream.read(1024 ** 2), b""):
                            if time.monotonic() > deadline:
                                raise TimeoutError()
                            if check:
                                check()
                            yield chunk
                with client.stream("POST", digest_path, content=chunks(),
                                   headers={"Content-Type": "application/octet-stream", "Content-Length": str(metadata["artifactSize"])}) as response:
                    response.raise_for_status()
                    # 엔진 오류 본문을 저장하거나 화면에 반환하지 않는다.
                    received = 0
                    for chunk in response.iter_bytes():
                        received += len(chunk)
                        if received > 2 * 1024 ** 2:
                            raise ValueError()
                        if time.monotonic() > deadline:
                            raise TimeoutError()
            if time.monotonic() > deadline:
                raise TimeoutError()
            if check:
                check()
            # 파일을 보낸 동안 외부에서 같은 이름을 등록했는지 다시 확인한다.
            if any(row.get("name") == metadata["model"] for row in self.models(client)):
                raise TrainedModelError(409, "TRAINED_MODEL_NAME_CONFLICT", "파일 전달 중 같은 이름의 모델이 등록됐습니다. 기존 모델을 보존했습니다.")
            created = self._json(client, "POST", "/api/create", body)
            if created.get("status") != "success":
                raise ValueError()
            installed = next((row for row in self.models(client) if row.get("name") == metadata["model"]), {})
            digest = str(installed.get("digest", "")).removeprefix("sha256:")
            show = self._json(client, "POST", "/api/show", {"model": metadata["model"]})
            if (not HASH.fullmatch(digest) or not self._compatible(show)
                    or not isinstance(show.get("details"), dict) or show["details"].get("quantization_level") != metadata["quantization"]):
                raise ValueError()
            return digest


class TrainedModelRuntime:
    """등록 요청을 짧게 접수하고 파일 검사와 엔진 등록은 별도 작업으로 처리한다."""

    def __init__(self, settings, importer=None):
        self.settings = settings
        configured = getattr(settings, "ai_trained_model_dir", "")
        self.cache = Path(configured) if configured else TRAINING_ROOT.parent / "ai-server/.runtime/trained-models"
        self.importer = importer or OllamaModelImporter(settings)
        self.tasks = {}

    @staticmethod
    def owns(model):
        return isinstance(model, str) and MODEL.fullmatch(model) is not None

    def _root(self):
        if not self.cache.is_absolute() or self.cache.is_symlink():
            raise TrainedModelError(503, "TRAINED_MODEL_NOT_CONFIGURED", "분석 서버의 완성 모델 보관 폴더를 확인하세요.")
        try:
            directory(self.cache, create=True)
        except (PackageError, OSError):
            raise TrainedModelError(503, "TRAINED_MODEL_STATE_UNAVAILABLE", "분석 서버의 완성 모델 보관 폴더에 접근하지 못했습니다.") from None
        return self.cache

    def _state_path(self, job_id):
        return self._root() / (str(UUID(str(job_id))) + ".json")

    def _load(self, job_id):
        path = self._state_path(job_id)
        if not path.exists() and not path.is_symlink():
            return None
        try:
            value = read_json(path)
            metadata_input(value["metadata"])
            if value["metadata"]["jobId"] != str(job_id) or value["status"] not in {"IMPORTING", "READY", "WAITING", "UNAVAILABLE"}:
                raise ValueError()
            if value["status"] == "READY" and not HASH.fullmatch(value.get("digest", "")):
                raise ValueError()
            return value
        except (PackageError, OSError, ValueError, TypeError, KeyError):
            raise TrainedModelError(503, "TRAINED_MODEL_STATE_INVALID", "저장된 완성 모델 등록 기록을 확인하지 못했습니다.") from None

    def _save(self, value):
        path = self._state_path(value["metadata"]["jobId"])
        descriptor, name = tempfile.mkstemp(prefix=".state-", dir=self._root())
        try:
            with os.fdopen(descriptor, "wb") as target:
                target.write(canonical(value))
                target.flush()
                os.fsync(target.fileno())
            os.replace(name, path)
        finally:
            Path(name).unlink(missing_ok=True)

    @staticmethod
    def _public(state):
        metadata = state["metadata"]
        result = {**metadata, "status": state["status"], "provider": "ollama", "trainedModel": True,
                  "trainingJobId": metadata["jobId"], "qualityEvaluated": False,
                  "message": state.get("message", "완성 모델의 등록 상태를 확인했습니다.")}
        if state.get("digest"):
            result["digest"] = state["digest"]
        if state.get("errorCode"):
            result["errorCode"] = state["errorCode"]
        if state["status"] == "IMPORTING" and state.get("phase") in IMPORT_PHASE_MESSAGES:
            result["phase"] = state["phase"]
            result["message"] = IMPORT_PHASE_MESSAGES[state["phase"]]
        return result

    def status(self, job_id):
        state = self._load(str(job_id))
        if state is None:
            return {"status": "NOT_STARTED", "jobId": str(job_id), "model": "doenaai-trained:web-" + str(job_id),
                    "message": "완성 모델 등록을 아직 요청하지 않았습니다."}
        if state["status"] == "IMPORTING":
            descriptor = os.open(self._root() / ".import.lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
            try:
                try:
                    fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    pass
                else:
                    state = {**state, "status": "UNAVAILABLE", "errorCode": "TRAINED_MODEL_IMPORT_INTERRUPTED",
                             "message": "서버 재시작으로 등록 작업이 중단됐습니다. 파일을 보존했으며 다시 등록을 요청할 수 있습니다."}
                    self._save(state)
            finally:
                os.close(descriptor)
        return self._public(state)

    def metadata_for(self, model, digest):
        """패키지 해시와 실제 설치 모델 해시를 구분하고 현재 설치 파일에 출처를 묶는다."""
        if not self.owns(model):
            return {}
        job_id = MODEL.fullmatch(model)[1]
        state = self._load(job_id)
        digest = str(digest or "").removeprefix("sha256:")
        if not state or state["status"] != "READY" or state.get("digest") != digest or state["metadata"]["model"] != model:
            raise TrainedModelError(409, "TRAINED_MODEL_DIGEST_CHANGED", "등록한 완성 모델의 설치 파일이 없거나 바뀌었습니다. 등록 상태를 다시 확인하세요.")
        return {"sourceModel": state["metadata"]["sourceModel"], "trainingJobId": job_id,
                "provider": "ollama", "trainedModel": True, "qualityEvaluated": False}

    async def prepare(self, metadata):
        metadata = metadata_input(metadata)
        existing = self._load(metadata["jobId"])
        if existing and existing["metadata"] != metadata:
            raise TrainedModelError(409, "TRAINED_MODEL_IMPORT_CONFLICT", "같은 학습 작업에 다른 완성 모델 파일을 등록할 수 없습니다.")
        if existing and existing["status"] == "READY":
            return self._public(existing)
        lock = os.open(self._root() / ".import.lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            os.close(lock)
            if existing and existing["status"] == "IMPORTING":
                return self._public(existing)
            raise TrainedModelError(409, "TRAINED_MODEL_IMPORT_BUSY", "다른 완성 모델을 등록 중입니다. 완료 후 다시 시도하세요.") from None
        state = {"metadata": metadata, "status": "IMPORTING", "message": "서명과 파일을 확인한 뒤 분석 엔진에 새 모델을 등록합니다."}
        try:
            self._save(state)
            task = asyncio.create_task(self._run(metadata, lock))
            self.tasks[metadata["jobId"]] = task
            def finished(done):
                self.tasks.pop(metadata["jobId"], None)
                if not done.cancelled():
                    # 저장 실패는 다음 상태 조회에서 중단으로 드러나며 원문 예외는 노출하지 않는다.
                    done.exception()
            task.add_done_callback(finished)
        except BaseException:
            fcntl.flock(lock, fcntl.LOCK_UN)
            os.close(lock)
            raise
        return self._public(state)

    async def _run(self, metadata, lock):
        stopped = threading.Event()
        worker = asyncio.get_running_loop().run_in_executor(None, self._perform, metadata, stopped)
        try:
            try:
                state = await asyncio.shield(worker)
            except asyncio.CancelledError:
                # 복사 작업이 끝나기 전 프로세스 잠금을 풀어 중복 등록을 허용하지 않는다.
                stopped.set()
                state = await worker
                self._save(state)
                raise
            self._save(state)
        finally:
            fcntl.flock(lock, fcntl.LOCK_UN)
            os.close(lock)

    def _perform(self, metadata, stopped=None):
        state = {"metadata": metadata, "status": "UNAVAILABLE"}
        temporary = None
        try:
            from trained_model_delivery import PACKAGE_FILES, verify_package
            deadline = time.monotonic() + getattr(self.settings, "ai_trained_model_import_timeout_seconds", 7200)
            def check():
                if stopped is not None and stopped.is_set():
                    raise TrainedModelError(503, "TRAINED_MODEL_IMPORT_INTERRUPTED", "서버 종료로 모델 등록을 중단했습니다. 파일을 보존했으며 다시 요청할 수 있습니다.")
                if time.monotonic() > deadline:
                    raise TimeoutError()
            check()
            secret = self.settings.training_transfer_key.get_secret_value()
            shared = Path(self.settings.training_shared_dir)
            if len(secret) < 32 or not self.settings.training_shared_dir or not shared.is_absolute() or shared.is_symlink() or not shared.is_dir():
                raise TrainedModelError(503, "TRAINED_MODEL_NOT_CONFIGURED", "분석 서버의 공유 폴더와 서명 검증 키를 설정하세요.")
            source = shared / "model-deliveries" / metadata["version"]
            if source.is_symlink() or source.parent.is_symlink():
                raise TrainedModelError(409, "TRAINED_MODEL_INTEGRITY_FAILED", "공유 폴더의 완성 모델 경로를 확인하세요.")
            if not source.is_dir() or any(not (source / name).is_file() for name in PACKAGE_FILES):
                return {**state, "status": "WAITING", "message": "공유 폴더의 완성 모델 파일이 모두 동기화되기를 기다립니다."}
            def phase(value):
                if value not in IMPORT_PHASE_MESSAGES:
                    raise ValueError()
                check()
                self._save({"metadata": metadata, "status": "IMPORTING", "phase": value,
                            "message": IMPORT_PHASE_MESSAGES[value]})
            ensure_local_files(source, PACKAGE_FILES, deadline, check=check, progress=phase)
            phase("VERIFYING")
            if verify_package(source, secret, check=check) != metadata:
                raise TrainedModelError(409, "TRAINED_MODEL_INTEGRITY_FAILED", "완성 모델의 서명과 요청 식별값이 일치하지 않습니다.")
            destination = self._root() / metadata["version"]
            cached = destination.exists() or destination.is_symlink()
            # 로컬 보관 사본과 분석 엔진의 새 가중치 사본에 필요한 공간을 함께 확보한다.
            required_space = metadata["artifactSize"] * (1 if cached else 2) + 128 * 1024 ** 2
            if shutil.disk_usage(self._root()).free < required_space:
                raise TrainedModelError(503, "TRAINED_MODEL_DISK_FULL", "완성 모델의 로컬 보관과 분석 엔진 등록에 필요한 디스크 공간이 부족합니다.")
            if cached:
                if verify_package(destination, secret, check=check) != metadata:
                    raise TrainedModelError(409, "TRAINED_MODEL_IMPORT_CONFLICT", "보관 중인 같은 버전의 완성 모델 내용이 다릅니다.")
            else:
                phase("COPYING")
                temporary = Path(tempfile.mkdtemp(prefix=".model-", dir=self._root()))
                for name in PACKAGE_FILES:
                    digest, size = hashlib.sha256(), 0
                    path = source / name
                    if path.is_symlink():
                        raise ValueError()
                    with path.open("rb") as stream:
                        descriptor = os.open(temporary / name, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
                        with os.fdopen(descriptor, "wb") as target:
                            for chunk in iter(lambda: stream.read(1024 ** 2), b""):
                                size += len(chunk)
                                if size > (MAX_WEIGHTS if name == "model.gguf" else 1024 ** 2):
                                    raise ValueError()
                                check()
                                digest.update(chunk)
                                target.write(chunk)
                            target.flush()
                            os.fsync(target.fileno())
                    if name == "model.gguf" and (digest.hexdigest() != metadata["artifactSha256"] or size != metadata["artifactSize"]):
                        raise ValueError()
                if verify_package(temporary, secret, check=check) != metadata:
                    raise ValueError()
                temporary.rename(destination)
                temporary = None
            check()
            phase("REGISTERING")
            actual = self.importer.register(metadata, destination / "model.gguf", deadline, check=check)
            if not isinstance(actual, str) or not HASH.fullmatch(actual):
                raise ValueError()
            return {**state, "status": "READY", "digest": actual,
                    "message": "완성 모델을 분석 엔진에 등록했습니다. 응답 검증 후 적용하세요. 답변 품질은 아직 평가하지 않았습니다."}
        except CloudModelFilesError as error:
            return {**state, "errorCode": error.code, "message": error.message}
        except TrainedModelError as error:
            return {**state, "errorCode": error.code, "message": error.message}
        except (TimeoutError, httpx.TimeoutException):
            return {**state, "errorCode": "TRAINED_MODEL_IMPORT_TIMEOUT", "message": "모델 등록 시간이 초과됐습니다. 설치 목록을 확인하세요. 현재 사용 모델은 변경하지 않았습니다."}
        except (PackageError, OSError, ValueError, TypeError, KeyError, AttributeError, ImportError, httpx.HTTPError):
            return {**state, "errorCode": "TRAINED_MODEL_IMPORT_FAILED", "message": "완성 모델의 서명·동기화·분석 엔진 등록을 확인하지 못했습니다. 현재 사용 모델은 유지합니다."}
        finally:
            if temporary is not None and temporary.exists():
                shutil.rmtree(temporary)
