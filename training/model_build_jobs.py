"""완료된 시험 학습으로 새 분석 모델을 만들고 진행 상태를 보관한다."""

import fcntl
import importlib.metadata
import json
import logging
import os
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path

from gpu_lease import GpuLease
from model_delivery import PackageError, read_json
from shared_transfer import write_json_atomic
from trained_model_delivery import (BASE_MODEL_REVISION, LLAMA_CPP_REVISION, MLX_LM_VERSION, checksum, create_package,
                                    identity, publish_package, validate_metadata, verify_package)
from training_jobs import TrainingJobError, job_id, now
from transfer_cases import private_directory

ACTIVE = {"PENDING", "FUSING", "CONVERTING", "QUANTIZING", "PUBLISHING"}
TERMINAL = {"SUCCEEDED", "FAILED", "CANCELLED"}
MIN_FREE_BYTES = 180 * 1024**3
MESSAGES = {
    "NOT_BUILT": "학습 결과로 분석 서버에서 사용할 새 모델을 만들 수 있습니다.",
    "PENDING": "새 모델 생성 작업을 접수했습니다.",
    "FUSING": "학습 어댑터를 기반 모델에 반영하고 변환용 가중치를 준비합니다.",
    "CONVERTING": "학습을 반영한 모델을 분석 서버용 GGUF 형식으로 변환합니다.",
    "QUANTIZING": "분석 서버에서 사용할 Q4_K_M 모델을 생성하고 형식을 검사합니다.",
    "PUBLISHING": "서명된 새 모델 패키지를 공유 폴더에 게시합니다.",
    "SUCCEEDED": "새 모델 생성과 공유 폴더 게시를 마쳤습니다. 분석 서버 등록과 응답 검증 후 적용하세요.",
    "FAILED": "새 모델을 생성하지 못했습니다. 기존 모델과 학습 결과는 보존했습니다.",
    "CANCELLED": "새 모델 생성을 취소했습니다. 기존 모델과 학습 결과는 보존했습니다.",
}
ERRORS = {
    "MODEL_BUILD_UNAVAILABLE": "학습 서버의 고정 버전 변환 도구와 MLX 실행 환경 설정을 확인하세요.",
    "MODEL_BUILD_DISK_SPACE": "새 모델 생성에는 작업 폴더에 180 GiB 이상의 여유 공간이 필요합니다.",
    "MODEL_BUILD_BUSY": "학습 또는 다른 모델 생성 작업이 진행 중입니다. 완료하거나 취소한 뒤 다시 시도하세요.",
    "MODEL_BUILD_SOURCE_INVALID": "학습 완료 상태와 어댑터·보고서·기반 모델 버전의 일치를 확인하세요.",
    "MODEL_BUILD_FAILED": "새 모델 병합·변환·양자화를 완료하지 못했습니다. 고정된 변환 도구와 모델 호환성을 확인하세요.",
    "MODEL_BUILD_PUBLISH_FAILED": "새 모델 패키지의 서명 검사 또는 공유 폴더 게시를 완료하지 못했습니다.",
    "MODEL_BUILD_TIMEOUT": "새 모델 생성 제한 시간을 초과해 실행 프로세스를 중단했습니다.",
    "MODEL_BUILD_INTERRUPTED": "학습 서버가 중단돼 이전 모델 생성 작업을 실패로 확정했습니다. 자동으로 다시 실행하지 않습니다.",
    "MODEL_BUILD_STATE_UNAVAILABLE": "모델 생성 작업 상태를 확인하거나 저장하지 못했습니다.",
    "INVALID_MODEL_BUILD_REQUEST": "모델 생성 요청은 작업 식별자와 빈 JSON 객체만 사용하세요.",
    "NOT_FOUND": "학습 작업을 찾을 수 없습니다.",
}
logger = logging.getLogger("doenaai.training.model_build")


class ModelBuildError(ValueError):
    """경로와 변환 도구 출력을 포함하지 않는 고정 오류다."""

    def __init__(self, code, http_status=409):
        self.code = code if code in ERRORS else "MODEL_BUILD_STATE_UNAVAILABLE"
        self.message, self.http_status = ERRORS[self.code], http_status
        super().__init__(self.message)


class ModelBuildStore:
    """학습 원본을 보존하면서 명시적으로 요청한 새 모델만 비동기로 생성한다."""

    def __init__(self, jobs, secret, *, state_dir=None, work_root=None, popen_factory=None,
                 timeout=4 * 3600, environ=None):
        self.jobs, self.secret = jobs, secret
        self.root = jobs.root
        self.state_dir = private_directory(state_dir or self.root / "datasets/model-build-jobs")
        self.work_root = private_directory(work_root or self.root / "outputs/model-builds")
        self.environment = os.environ if environ is None else environ
        self.popen_factory = popen_factory or subprocess.Popen
        self.timeout = timeout
        self.lock = threading.RLock()
        self.stopping, self.cancel_requested = threading.Event(), threading.Event()
        self.owner, self.worker, self.process = None, None, None
        self.unsafe_process = False
        self.deadline = None
        self.gpu_lease = GpuLease(jobs.state_dir / ".gpu.lock")
        self._claim_owner()

    def _claim_owner(self):
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
                    self._set(record, "FAILED", record["progress"], "MODEL_BUILD_INTERRUPTED")
                if record:
                    # 작업 잠금을 되찾은 뒤에만 재시작 전에 남은 일회성 파일을 정리한다.
                    for attempt in range(1, record["attempt"] + 1):
                        try:
                            self._cleanup_attempt(record["jobId"], attempt,
                                self.work_root / record["jobId"] / ("attempt-" + str(attempt)))
                        except (OSError, ValueError):
                            logger.error("이전 모델 변환 폴더 정리 실패 code=MODEL_BUILD_CLEANUP_FAILED")
            return True

    def _path(self, identifier):
        try:
            return self.state_dir / (job_id(identifier) + ".json")
        except TrainingJobError:
            raise ModelBuildError("INVALID_MODEL_BUILD_REQUEST", 422) from None

    def _read(self, path):
        if not path.exists() and not path.is_symlink():
            return None
        try:
            value = read_json(path)
            if (value.get("jobId") != path.stem or value.get("status") not in ACTIVE | TERMINAL
                    or type(value.get("progress")) is not int or not 0 <= value["progress"] <= 100
                    or type(value.get("attempt")) is not int or not 1 <= value["attempt"] <= 1000):
                raise ValueError()
            self._path(value["jobId"])
            if value["status"] == "SUCCEEDED":
                validate_metadata(value.get("result"))
            return value
        except (ValueError, OSError, TypeError):
            raise ModelBuildError("MODEL_BUILD_STATE_UNAVAILABLE", 503) from None

    def _set(self, record, status, progress, error=None, result=None):
        record.update(status=status, progress=progress, updatedAt=now())
        if error:
            record["errorCode"] = error
        if result is not None:
            record["result"] = validate_metadata(result)
        write_json_atomic(self._path(record["jobId"]), record)
        logger.info("모델 생성 상태 변경 status=%s code=%s", status, error or "NONE")

    def _source(self, identifier):
        try:
            source = self.jobs.model_build_source(identifier)
            if source.get("target", {}).get("revision") != BASE_MODEL_REVISION:
                raise ModelBuildError("MODEL_BUILD_SOURCE_INVALID")
            return source
        except TrainingJobError as error:
            raise ModelBuildError("NOT_FOUND" if error.code == "NOT_FOUND" else "MODEL_BUILD_SOURCE_INVALID",
                                  404 if error.code == "NOT_FOUND" else 409) from None
        except (PackageError, OSError, ValueError, TypeError):
            raise ModelBuildError("MODEL_BUILD_SOURCE_INVALID") from None

    def _configuration(self):
        """학습 서버의 로컬 고정 도구만 검사하며 다운로드나 모델 실행은 하지 않는다."""
        repository = Path(self.environment.get("DOENAAI_TRAINING_LLAMA_CPP_DIR", ""))
        python = Path(self.environment.get("DOENAAI_TRAINING_CONVERTER_PYTHON", ""))
        binary_hash = self.environment.get("DOENAAI_TRAINING_QUANTIZER_SHA256", "")
        if (not repository.is_absolute() or repository.is_symlink() or not repository.is_dir()
                or not python.is_absolute() or not python.is_file() or not os.access(python, os.X_OK)
                or not re.fullmatch(r"[0-9a-f]{64}", binary_hash)):
            return None, "MODEL_BUILD_UNAVAILABLE"
        try:
            converter, quantizer = repository / "convert_hf_to_gguf.py", repository / "build/bin/llama-quantize"
            if (converter.is_symlink() or not converter.is_file() or quantizer.is_symlink() or not quantizer.is_file()
                    or not os.access(quantizer, os.X_OK) or checksum(quantizer)[0] != binary_hash
                    or sys.platform != "darwin" or importlib.metadata.version("mlx-lm") != MLX_LM_VERSION):
                return None, "MODEL_BUILD_UNAVAILABLE"
            revision = subprocess.run(["git", "-C", str(repository), "rev-parse", "HEAD"], check=True,
                                      stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=3, text=True).stdout.strip()
            dirty = subprocess.run(["git", "-C", str(repository), "status", "--porcelain", "--untracked-files=no"], check=True,
                                   stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=3, text=True).stdout.strip()
            if revision != LLAMA_CPP_REVISION or dirty:
                return None, "MODEL_BUILD_UNAVAILABLE"
            subprocess.run([str(python), "-c", "import sys, importlib.util, importlib.metadata as m; assert sys.version_info >= (3, 10); "
                            "assert all(importlib.util.find_spec(name) is not None for name in "
                            "('torch', 'numpy', 'safetensors', 'sentencepiece', 'transformers')); "
                            "assert m.version('torch').split('+')[0] == '2.11.0'; "
                            "assert m.version('transformers') == '4.57.6'; "
                            "assert m.version('numpy').startswith('2.2.') and tuple(map(int, m.version('numpy').split('.'))) >= (2, 2, 6)"],
                           check=True, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, timeout=5)
            if shutil.disk_usage(self.work_root).free < MIN_FREE_BYTES:
                return None, "MODEL_BUILD_DISK_SPACE"
        except (OSError, ValueError, subprocess.SubprocessError, importlib.metadata.PackageNotFoundError):
            return None, "MODEL_BUILD_UNAVAILABLE"
        return {"repository": repository, "python": python, "quantizerSha256": binary_hash}, None

    def _public(self, record, reason=None):
        result = {key: record[key] for key in ("jobId", "status", "progress", "createdAt", "updatedAt") if key in record}
        code = record.get("errorCode")
        message = ERRORS[code] if code in ERRORS else (
            ERRORS[reason] if reason in ERRORS and record["status"] == "NOT_BUILT" else MESSAGES[record["status"]])
        result.update(message=message, available=reason is None, reasonCode=reason or "READY")
        if code in ERRORS:
            result["errorCode"] = code
        if record["status"] == "SUCCEEDED":
            result["result"] = validate_metadata(record["result"])
        return result

    def get(self, identifier):
        path = self._path(identifier)
        with self.lock:
            record = self._read(path)
            if record and record["status"] == "SUCCEEDED":
                return self._public(record)
            self._source(identifier)
            _, reason = self._configuration()
            if not self._claim_owner() or self.unsafe_process or self.stopping.is_set():
                reason = "MODEL_BUILD_BUSY"
            if reason is None:
                probe = GpuLease(self.jobs.state_dir / ".gpu.lock")
                try:
                    if not probe.acquire():
                        reason = "MODEL_BUILD_BUSY"
                finally:
                    probe.release()
            if record:
                return self._public(record, reason)
            return self._public({"jobId": identifier, "status": "NOT_BUILT", "progress": 0}, reason)

    def submit(self, identifier, body):
        if body != {}:
            raise ModelBuildError("INVALID_MODEL_BUILD_REQUEST", 422)
        path = self._path(identifier)
        with self.lock:
            previous = self._read(path)
            if previous and previous["status"] in ACTIVE | {"SUCCEEDED"}:
                return self._public(previous)
            if previous and previous["attempt"] >= 1000:
                raise ModelBuildError("MODEL_BUILD_STATE_UNAVAILABLE", 503)
            source = self._source(identifier)
            configuration, reason = self._configuration()
            if reason:
                raise ModelBuildError(reason, 503)
            if (not self._claim_owner() or self.stopping.is_set() or self.unsafe_process
                    or self.worker and self.worker.is_alive()):
                raise ModelBuildError("MODEL_BUILD_BUSY")
            if not self.gpu_lease.acquire():
                raise ModelBuildError("MODEL_BUILD_BUSY")
            created = now()
            record = {"jobId": identifier, "status": "PENDING", "progress": 0, "createdAt": created, "updatedAt": created,
                      "attempt": (previous or {}).get("attempt", 0) + 1, "target": source["target"],
                      "adapterSha256": source["adapterSha256"]}
            try:
                write_json_atomic(path, record)
                self.cancel_requested.clear()
                self.worker = threading.Thread(target=self._worker_entry, args=(identifier, configuration), daemon=True, name="model-build")
                self.worker.start()
            except Exception:
                self.gpu_lease.release()
                self._set(record, "FAILED", 0, "MODEL_BUILD_STATE_UNAVAILABLE")
                raise ModelBuildError("MODEL_BUILD_STATE_UNAVAILABLE", 503) from None
            return self._public(record)

    def cancel(self, identifier, body):
        if body != {}:
            raise ModelBuildError("INVALID_MODEL_BUILD_REQUEST", 422)
        with self.lock:
            record = self._read(self._path(identifier))
            if record is None:
                return self.get(identifier)
            if record["status"] in ACTIVE:
                if not self._claim_owner():
                    raise ModelBuildError("MODEL_BUILD_BUSY")
                self.cancel_requested.set()
            return self._public(record)

    def _check_cancel(self):
        if self.stopping.is_set() or self.cancel_requested.is_set():
            raise InterruptedError()
        if self.deadline is not None and time.monotonic() > self.deadline:
            raise ModelBuildError("MODEL_BUILD_TIMEOUT")

    def _stop_process(self, process):
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
        finally:
            if process.poll() is None:
                self.unsafe_process = True

    def _phase(self, identifier, status, progress):
        with self.lock:
            self._check_cancel()
            record = self._read(self._path(identifier))
            self._set(record, status, progress)

    def _execute(self, identifier, source, directory, configuration):
        (directory / "export").mkdir(mode=0o700, exist_ok=False)
        progress = directory / "progress.json"
        arguments = [sys.executable, str(self.root / "model_export_worker.py"), "--model", str(source["model_dir"]),
                     "--adapter", str(source["adapter"]), "--output", str(directory / "export"),
                     "--llama-cpp", str(configuration["repository"]), "--converter-python", str(configuration["python"]),
                     "--quantizer-sha256", configuration["quantizerSha256"], "--progress", str(progress)]
        environment = {key: value for key, value in self.environment.items() if key in {"PATH", "HOME", "TMPDIR", "LANG", "LC_ALL"}}
        environment.update(HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1", HF_DATASETS_OFFLINE="1", HF_HUB_DISABLE_TELEMETRY="1",
                           DOENAAI_MODEL_BUILD_LOCK_FDS=f"{self.owner},{self.gpu_lease.descriptor}")
        self._check_cancel()
        process = self.popen_factory(arguments, cwd=self.root, env=environment, stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, shell=False, start_new_session=True,
            pass_fds=(self.owner, self.gpu_lease.descriptor))
        self.process = process
        started, last = time.monotonic(), None
        try:
            while process.poll() is None:
                self._check_cancel()
                if time.monotonic() - started > self.timeout:
                    raise ModelBuildError("MODEL_BUILD_TIMEOUT")
                if progress.is_file() and not progress.is_symlink():
                    try:
                        state = read_json(progress)
                        step = (state.get("status"), state.get("progress"))
                        if (set(state) == {"status", "progress"} and step[0] in {"FUSING", "CONVERTING", "QUANTIZING"}
                                and type(step[1]) is int and 1 <= step[1] <= 94 and step != last):
                            self._phase(identifier, *step)
                            last = step
                    except (PackageError, OSError, ValueError):
                        pass
                self.cancel_requested.wait(0.1)
            self._check_cancel()
            if process.returncode != 0:
                raise ModelBuildError("MODEL_BUILD_FAILED")
        finally:
            self._stop_process(process)
            self.process = None

    def _same_source(self, record):
        source = self._source(record["jobId"])
        if source["target"] != record["target"] or source["adapterSha256"] != record["adapterSha256"]:
            raise ModelBuildError("MODEL_BUILD_SOURCE_INVALID")
        return source

    def _existing_package(self, package, source, identifier):
        metadata = verify_package(package, self.secret, check=self._check_cancel)
        summary = read_json(package / "evaluation-summary.json")
        if (metadata["jobId"] != identifier or metadata["baseModel"] != source["target"]["baseModel"]
                or metadata["baseRevision"] != source["target"]["revision"]
                or summary.get("sourceAdapterSha256") != source["adapterSha256"]):
            raise ModelBuildError("MODEL_BUILD_SOURCE_INVALID")
        return metadata

    def _worker_entry(self, identifier, configuration):
        self.deadline = time.monotonic() + self.timeout
        try:
            self._run(identifier, configuration)
        except Exception:
            logger.error("모델 생성 상태 저장 실패 code=MODEL_BUILD_STATE_UNAVAILABLE")
        finally:
            self.deadline = None
            if not self.unsafe_process:
                self.gpu_lease.release()

    def _cleanup_attempt(self, identifier, attempt, directory):
        """직접 만든 일회성 변환 폴더만 지우며 학습 원본과 서명 완료 패키지는 보존한다."""
        expected_parent = self.work_root / job_id(identifier)
        if (directory != expected_parent / ("attempt-" + str(attempt)) or expected_parent.is_symlink()
                or self.work_root.is_symlink()):
            raise ModelBuildError("MODEL_BUILD_STATE_UNAVAILABLE")
        if directory.is_symlink():
            directory.unlink()
        elif directory.exists():
            shutil.rmtree(directory)

    def _run(self, identifier, configuration):
        phase, temporary, attempt = "FUSING", None, None
        try:
            record = self._read(self._path(identifier))
            source = self._same_source(record)
            attempt = record["attempt"]
            parent = private_directory(self.work_root / identifier)
            directory = parent / ("attempt-" + str(attempt))
            directory.mkdir(mode=0o700, exist_ok=False)
            temporary = directory
            version, _ = identity(identifier)
            package = self.root / "outputs/model-build-packages" / version
            if package.exists() or package.is_symlink():
                result = self._existing_package(package, source, identifier)
            else:
                self._phase(identifier, "FUSING", 5)
                self._execute(identifier, source, directory, configuration)
                self._same_source(record)
                report = read_json(directory / "export/export_report.json")
                artifact = directory / "export/model.gguf"
                actual, size = checksum(artifact, check=self._check_cancel)
                if (report != {"status": "complete", "artifactSha256": actual, "artifactSize": size,
                               "quantization": "Q4_K_M", "architecture": "qwen35",
                               "toolchain": {"mlxLmVersion": MLX_LM_VERSION, "llamaCppRevision": LLAMA_CPP_REVISION}}):
                    raise ModelBuildError("MODEL_BUILD_FAILED")
                summary = {**source["summary"], "sourceAdapterSha256": source["adapterSha256"],
                           "notice": "학습 결과를 반영한 새 모델입니다. 답변 품질 평가와 운영 적용 승인은 별도로 진행해야 합니다."}
                result = create_package(artifact, summary, package, identifier,
                    {"modelId": source["target"]["baseModel"], "revision": source["target"]["revision"]}, self.secret, check=self._check_cancel)
            self._check_cancel()
            phase = "PUBLISHING"
            self._phase(identifier, "PUBLISHING", 95)
            self._same_source(record)
            if publish_package(package, self.jobs.receiver.shared_dir, self.secret, check=self._check_cancel) != result:
                raise ModelBuildError("MODEL_BUILD_PUBLISH_FAILED")
            with self.lock:
                self._check_cancel()
                self._set(self._read(self._path(identifier)), "SUCCEEDED", 100, result=result)
        except InterruptedError:
            with self.lock:
                current = self._read(self._path(identifier))
                self._set(current, "CANCELLED", current["progress"])
        except Exception as error:
            code = error.code if isinstance(error, ModelBuildError) else (
                "MODEL_BUILD_PUBLISH_FAILED" if phase == "PUBLISHING" else "MODEL_BUILD_FAILED")
            with self.lock:
                current = self._read(self._path(identifier))
                self._set(current, "FAILED", current["progress"], code)
        finally:
            if temporary is not None and not self.unsafe_process:
                try:
                    self._cleanup_attempt(identifier, attempt, temporary)
                except (OSError, ValueError):
                    logger.error("일회성 모델 변환 폴더 정리 실패 code=MODEL_BUILD_CLEANUP_FAILED")

    def close(self):
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
