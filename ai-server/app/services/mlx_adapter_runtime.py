"""공유 폴더의 검증된 어댑터만 로컬 MLX 추론 작업에 연결한다."""

from __future__ import annotations

import asyncio
import fcntl
import json
import os
import re
import shutil
import signal
import sys
import tempfile
import weakref
from contextlib import contextmanager
from pathlib import Path
from uuid import UUID

TRAINING_ROOT = Path(__file__).resolve().parents[3] / "training"
if str(TRAINING_ROOT) not in sys.path:
    sys.path.insert(0, str(TRAINING_ROOT))

from model_delivery import (PACKAGE_FILES, PackageError, directory, read_json, verify_package,
                            copy_file, canonical, write_file)  # noqa: E402
from model_registry import QWEN_BASE, QWEN_SOURCE  # noqa: E402

PREFIX = "doenaai-mlx:"
MODEL = re.compile(r"doenaai-mlx:web-([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})\Z")
HASH = re.compile(r"[0-9a-f]{64}\Z")
METADATA = {"jobId", "version", "manifestSha256", "sourceModel", "baseModel", "baseRevision"}


class MlxAdapterError(Exception):
    """모델 경로와 요청 원문을 노출하지 않는 어댑터 실행 오류다."""

    def __init__(self, status, code, message):
        super().__init__(message)
        self.status, self.code, self.message = status, code, message


class MlxAdapterRuntime:
    """로컬 복사와 검증을 마친 시험 어댑터만 별도 모델로 제공한다."""

    def __init__(self, settings, runner=None):
        self.settings = settings
        self.runner = runner
        configured = getattr(settings, "ai_mlx_adapter_dir", "")
        self.cache = Path(configured) if configured else TRAINING_ROOT.parent / "ai-server/.runtime/mlx-adapters"
        self._import_locks = weakref.WeakKeyDictionary()
        self._inference_locks = weakref.WeakKeyDictionary()

    def _loop_lock(self, locks):
        """동기 의존성 생성과 서로 다른 검사 이벤트 루프에서도 잠금을 지연 생성한다."""
        loop = asyncio.get_running_loop()
        if loop not in locks:
            locks[loop] = asyncio.Lock()
        return locks[loop]

    @contextmanager
    def _gpu_lock(self):
        """여러 분석 서버 워커가 같은 장비에서 GPU 프로세스를 겹쳐 띄우지 않는다."""
        try:
            directory(self.cache, create=True)
            descriptor = os.open(self.cache / ".inference.lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        except (OSError, ValueError):
            raise MlxAdapterError(503, "MLX_STATE_UNAVAILABLE", "분석 서버의 어댑터 추론 잠금을 확인하지 못했습니다.") from None
        try:
            try:
                fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise MlxAdapterError(409, "MLX_BUSY", "다른 어댑터 추론이 진행 중입니다. 완료 후 다시 시도하세요.") from None
            except OSError:
                raise MlxAdapterError(503, "MLX_STATE_UNAVAILABLE", "분석 서버의 어댑터 추론 잠금을 확보하지 못했습니다.") from None
            yield
        finally:
            fcntl.flock(descriptor, fcntl.LOCK_UN)
            os.close(descriptor)

    @staticmethod
    def owns(model):
        return isinstance(model, str) and MODEL.fullmatch(model) is not None

    @staticmethod
    def _metadata(value):
        try:
            if not isinstance(value, dict) or set(value) != METADATA:
                raise ValueError()
            identifier = str(UUID(value["jobId"]))
            if (identifier != value["jobId"] or value["version"] != "web-" + identifier
                    or value["sourceModel"] != QWEN_SOURCE or value["baseModel"] != QWEN_BASE
                    or not isinstance(value["manifestSha256"], str) or not HASH.fullmatch(value["manifestSha256"])
                    or not isinstance(value["baseRevision"], str) or not re.fullmatch(r"[0-9a-f]{40}", value["baseRevision"])):
                raise ValueError()
            return dict(value)
        except (ValueError, TypeError, KeyError, AttributeError):
            raise MlxAdapterError(422, "MLX_ADAPTER_REQUEST_INVALID", "학습 작업과 어댑터 버전의 식별값을 확인하세요.") from None

    def _secret(self):
        secret = self.settings.training_transfer_key.get_secret_value()
        if len(secret) < 32:
            raise MlxAdapterError(503, "MLX_NOT_CONFIGURED", "어댑터 검증에 사용할 서버 전송 키를 설정하세요.")
        return secret

    def _configuration(self, metadata):
        try:
            model_dir = Path(getattr(self.settings, "ai_mlx_model_dir", ""))
            python = Path(getattr(self.settings, "ai_mlx_python", ""))
            if (not model_dir.is_absolute() or model_dir.is_symlink() or not model_dir.is_dir()
                    or not python.is_absolute() or not python.is_file() or not os.access(python, os.X_OK)
                    or not self.cache.is_absolute() or self.cache.is_symlink()):
                raise ValueError()
            manifest = read_json(model_dir / "download_manifest.json")
            config = read_json(model_dir / "config.json")
            text = config.get("text_config", config)
            if (manifest.get("modelId") != metadata["baseModel"] or manifest.get("revision") != metadata["baseRevision"]
                    or config.get("model_type") != "qwen3_5" or not isinstance(text, dict)
                    or text.get("tie_word_embeddings") is not False
                    or not any(path.is_file() and not path.is_symlink() for path in model_dir.glob("*.safetensors"))):
                raise ValueError()
            return model_dir, python
        except (OSError, ValueError, TypeError, KeyError):
            raise MlxAdapterError(503, "MLX_NOT_CONFIGURED", "분석 서버의 MLX 실행용 Python과 학습 당시 기반 모델의 로컬 경로·고정 버전을 먼저 설정하세요.") from None

    def _verified(self, package, metadata):
        try:
            verified = verify_package(package, self._secret())
            manifest = read_json(package / "manifest.json")
            if (verified["manifestSha256"] != metadata["manifestSha256"] or verified["version"] != metadata["version"]
                    or manifest["model"] != {"modelId": metadata["baseModel"], "revision": metadata["baseRevision"]}):
                raise PackageError("학습 결과와 어댑터의 식별값이 다릅니다.")
            return manifest
        except (OSError, ValueError, TypeError, KeyError):
            raise MlxAdapterError(409, "MLX_ADAPTER_INTEGRITY_FAILED", "어댑터의 서명·파일 해시·기반 모델 버전을 확인하지 못했습니다.") from None

    def _local(self, model, *, require_ready=True):
        if not self.owns(model):
            raise MlxAdapterError(404, "MLX_ADAPTER_NOT_FOUND", "준비된 학습 어댑터를 찾을 수 없습니다.")
        try:
            version = model.removeprefix(PREFIX)
            package = directory(self.cache / version)
            metadata = self._metadata(read_json(package / ".runtime-metadata.json"))
            if metadata["version"] != version:
                raise ValueError()
            manifest = self._verified(package, metadata)
            model_dir, python = self._configuration(metadata)
            if require_ready and read_json(package / ".runtime-ready.json") != {"manifestSha256": metadata["manifestSha256"]}:
                raise ValueError()
            return metadata, manifest, package, model_dir, python
        except MlxAdapterError:
            raise
        except (OSError, ValueError, TypeError, KeyError):
            raise MlxAdapterError(409, "MLX_ADAPTER_NOT_READY", "분석 서버에 준비된 어댑터 파일을 다시 확인하세요.") from None

    def source_model(self, model):
        return self._local(model)[0]["sourceModel"]

    async def prepare(self, metadata):
        metadata = self._metadata(metadata)
        model = PREFIX + metadata["version"]
        result = {"model": model, "digest": metadata["manifestSha256"], "sourceModel": metadata["sourceModel"],
                  "adapterId": metadata["jobId"], "qualityEvaluated": False}
        try:
            model_dir, _ = self._configuration(metadata)
            self._secret()
            async with self._loop_lock(self._import_locks):
                destination = self.cache / metadata["version"]
                if destination.exists() or destination.is_symlink():
                    existing, _, _, _, _ = self._local(model, require_ready=False)
                    if existing != metadata:
                        raise MlxAdapterError(409, "MLX_ADAPTER_CONFLICT", "같은 버전의 어댑터 식별값이 다릅니다.")
                else:
                    shared = Path(self.settings.training_shared_dir)
                    if not self.settings.training_shared_dir or not shared.is_absolute() or shared.is_symlink() or not shared.is_dir():
                        raise MlxAdapterError(503, "MLX_NOT_CONFIGURED", "분석 서버의 학습 결과 공유 폴더를 설정하세요.")
                    source = shared / "model-deliveries" / metadata["version"]
                    if source.is_symlink() or source.parent.is_symlink():
                        raise MlxAdapterError(409, "MLX_ADAPTER_INTEGRITY_FAILED", "공유 폴더의 어댑터 경로를 확인하세요.")
                    if not source.is_dir() or any(not (source / name).is_file() for name in PACKAGE_FILES):
                        return {**result, "status": "WAITING", "message": "공유 폴더의 학습 결과 파일이 모두 동기화되기를 기다립니다."}
                    self._verified(source, metadata)
                    directory(self.cache, create=True)
                    temporary = Path(tempfile.mkdtemp(prefix=".prepare-", dir=self.cache))
                    try:
                        for name in PACKAGE_FILES:
                            copy_file(source / name, temporary / name)
                        self._verified(temporary, metadata)
                        write_file(temporary / ".runtime-metadata.json", canonical(metadata))
                        temporary.rename(destination)
                    finally:
                        if temporary.exists():
                            shutil.rmtree(temporary)
                ready = destination / ".runtime-ready.json"
                try:
                    checked = await self._execute(model_dir, destination, {"check": True}, check=True)
                    if checked != {"available": True}:
                        raise MlxAdapterError(503, "MLX_NOT_CONFIGURED", "분석 서버의 Apple Silicon 및 MLX 실행 환경을 확인하지 못했습니다.")
                except MlxAdapterError:
                    ready.unlink(missing_ok=True)
                    raise
                if not ready.exists():
                    write_file(ready, canonical({"manifestSha256": metadata["manifestSha256"]}))
            return {**result, "status": "READY", "message": "어댑터 파일과 실행 환경을 준비했습니다. 실제 응답 검증 후 적용할 수 있으며 답변 품질은 아직 평가하지 않았습니다."}
        except MlxAdapterError as error:
            return {**result, "status": "UNAVAILABLE", "errorCode": error.code, "message": error.message}
        except (OSError, ValueError, TypeError):
            return {**result, "status": "UNAVAILABLE", "errorCode": "MLX_ADAPTER_PREPARE_FAILED",
                    "message": "분석 서버의 어댑터 준비를 완료하지 못했습니다. 기존 사용 모델은 유지합니다."}

    async def list(self):
        models = []
        if not self.cache.is_dir() or self.cache.is_symlink():
            return {"models": models}
        for path in sorted(self.cache.glob("web-*"))[:100]:
            try:
                metadata, manifest, _, _, _ = self._local(PREFIX + path.name)
                models.append({"name": PREFIX + metadata["version"], "digest": metadata["manifestSha256"],
                               "size": sum(item["size"] for item in manifest["files"]),
                               "sourceModel": metadata["sourceModel"], "adapterId": metadata["jobId"],
                               "provider": "mlx", "qualityEvaluated": False})
            except MlxAdapterError:
                continue
        return {"models": models}

    async def show(self, model):
        metadata, _, _, _, _ = self._local(model)
        return {"capabilities": ["completion", "thinking"], "sourceModel": metadata["sourceModel"],
                "adapterId": metadata["jobId"], "provider": "mlx", "qualityEvaluated": False}

    async def chat(self, **body):
        metadata, _, package, model_dir, _ = self._local(body.get("model"))
        messages, schema = body.get("messages"), body.get("format")
        options = body.get("options") or {}
        thinking = body.get("think", False)
        effort = thinking if isinstance(thinking, str) else None
        if effort in {"low", "medium", "high"}:
            thinking = True
        if (body.get("stream") is not False or not isinstance(messages, list) or not messages
                or len(messages) > 100 or not isinstance(schema, dict)
                or not isinstance(options, dict) or options.get("temperature", 0) != 0
                or type(thinking) is not bool
                or any(not isinstance(item, dict) or set(item) != {"role", "content"}
                       or item["role"] not in {"system", "user", "assistant"}
                       or not isinstance(item["content"], str) for item in messages)):
            raise MlxAdapterError(422, "MLX_REQUEST_INVALID", "어댑터 추론 요청의 대화와 JSON 응답 형식을 확인하세요.")
        maximum = options.get("num_predict", 8192)
        if type(maximum) is not int or not 1 <= maximum <= 16384:
            raise MlxAdapterError(422, "MLX_REQUEST_INVALID", "어댑터 추론의 출력 한도를 확인하세요.")
        payload = {"messages": messages, "schema": schema, "maxTokens": maximum, "thinkingEnabled": thinking}
        if effort is not None:
            payload["thinkingEffort"] = effort
        if len(canonical(payload)) > 2 * 1024 * 1024:
            raise MlxAdapterError(413, "MLX_REQUEST_INVALID", "어댑터 추론 입력의 허용 크기를 초과했습니다.")
        async with self._loop_lock(self._inference_locks):
            # 대기 중 파일이 달라진 요청은 실행 직전에 다시 검사한다.
            self._local(body["model"])
            with self._gpu_lock():
                result = await self._execute(model_dir, package, payload)
        try:
            if (set(result) != {"content", "finishReason", "promptTokens", "generationTokens"}
                    or not isinstance(result["content"], str) or len(result["content"].encode()) > 2 * 1024 * 1024
                    or result["finishReason"] not in {"stop", "length"}
                    or any(type(result[key]) is not int or result[key] < 0 for key in ("promptTokens", "generationTokens"))):
                raise ValueError()
        except (ValueError, TypeError, KeyError):
            raise MlxAdapterError(502, "MLX_RESPONSE_INVALID", "어댑터 추론 결과의 형식을 확인하지 못했습니다.") from None
        return {"model": PREFIX + metadata["version"], "message": {"content": result["content"]},
                "done": True, "done_reason": result["finishReason"],
                "prompt_eval_count": result["promptTokens"], "eval_count": result["generationTokens"]}

    async def _execute(self, model_dir, adapter_dir, payload, *, check=False):
        if self.runner is not None:
            return await self.runner(model_dir, adapter_dir, payload, check=check)
        python = str(Path(self.settings.ai_mlx_python))
        timeout = 15 if check else self.settings.ai_request_timeout_seconds
        environment = {"PATH": "/usr/bin:/bin:/usr/sbin:/sbin", "LANG": "en_US.UTF-8", "LC_ALL": "en_US.UTF-8",
                       "HF_HUB_OFFLINE": "1", "TRANSFORMERS_OFFLINE": "1", "TOKENIZERS_PARALLELISM": "false"}
        process = None
        with tempfile.TemporaryDirectory(prefix="doenaai-mlx-") as temporary:
            workspace = Path(temporary)
            os.chmod(workspace, 0o700)
            request, response = workspace / "request.json", workspace / "response.json"
            write_file(request, canonical(payload))
            arguments = [python, str(TRAINING_ROOT / "mlx_adapter_infer.py"), "--model", str(model_dir),
                         "--adapter", str(adapter_dir), "--request", str(request), "--response", str(response)]
            if check:
                arguments.append("--check")
            try:
                process = await asyncio.create_subprocess_exec(*arguments, stdin=asyncio.subprocess.DEVNULL,
                    stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
                    env=environment, start_new_session=True)
                await asyncio.wait_for(process.wait(), timeout)
                if process.returncode != 0 or not response.is_file() or response.is_symlink() or response.stat().st_size > 2 * 1024 * 1024:
                    raise MlxAdapterError(503, "MLX_EXECUTION_FAILED", "어댑터 추론을 완료하지 못했습니다. 분석 서버의 모델과 MLX 실행 환경을 확인하세요.")
                return json.loads(response.read_text(encoding="utf-8"))
            except asyncio.TimeoutError:
                raise MlxAdapterError(504, "MLX_TIMEOUT", "어댑터 추론 제한 시간을 초과했습니다. 기존 모델 선택은 변경하지 않았습니다.") from None
            except (OSError, ValueError, TypeError):
                raise MlxAdapterError(503, "MLX_EXECUTION_FAILED", "분석 서버의 MLX 추론 실행을 확인하지 못했습니다.") from None
            finally:
                if process is not None and process.returncode is None:
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    await process.wait()
