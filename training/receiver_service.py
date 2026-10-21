"""AI 학습 서버에서 공유 폴더의 학습 사례를 검증하고 수신 상태를 반환한다."""

import argparse
import json
import logging
import os
import re
import shutil
import tempfile
import threading
from collections import OrderedDict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from dataset_contract import DatasetError, VALIDATION_FEATURES, strict_json
from shared_transfer import HASH, verify_signature, write_json_atomic
from transfer_cases import MAX_BYTES, TransferError, checked_cases, content_hash, private_directory, receive
from receiver_config import load_receiver_env
from training_jobs import TrainingJobError, TrainingJobStore


ROOT = Path(__file__).resolve().parent
DEFAULT_STATE = ROOT / "datasets/receiver-state"
DEFAULT_STAGING = ROOT / "datasets/transfer-staging"
DEFAULT_INBOX = ROOT / "datasets/incoming"
STATUS_PATH = re.compile(r"/api/training/transfers/([0-9a-f]{64})\Z")
INTAKE_PATH = "/api/training/intake"
CAPABILITIES_PATH = "/api/training/capabilities"
JOBS_PATH = "/api/training/jobs"
JOB_PATH = re.compile(r"/api/training/jobs/([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})\Z")
CANCEL_PATH = re.compile(r"/api/training/jobs/([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})/cancel\Z")
logger = logging.getLogger("doenaai.training.receiver")


class ReceiverStore:
    """동기화 지연·재시작을 견디도록 알림과 수신증을 디스크에 보관한다."""

    def __init__(self, shared_dir, state_dir=DEFAULT_STATE, staging=DEFAULT_STAGING, inbox=DEFAULT_INBOX):
        self.shared_dir = Path(shared_dir)
        self.state_dir = private_directory(state_dir)
        self.staging = private_directory(staging)
        self.inbox = private_directory(inbox)
        self.lock = threading.RLock()
        self._logged_events = OrderedDict()

    def log_event(self, checksum, code, message, *, level=logging.INFO, cases=None, size=None):
        """동일 파일의 같은 상태는 반복 출력하지 않고 원문·주소·서명은 기록하지 않는다."""
        key = (checksum, code)
        with self.lock:
            if key in self._logged_events:
                return
            self._logged_events[key] = None
            if len(self._logged_events) > 2048:
                self._logged_events.popitem(last=False)
        transfer = checksum[:12] if isinstance(checksum, str) and HASH.fullmatch(checksum) else "-"
        logger.log(level, "%s code=%s transfer=%s cases=%s bytes=%s", message, code, transfer,
                   cases if type(cases) is int else "-", size if type(size) is int else "-")

    def _path(self, checksum):
        if not HASH.fullmatch(checksum):
            raise TransferError("전송 해시가 올바르지 않습니다.")
        return self.state_dir / f"{checksum}.json"

    def status(self, checksum):
        """수신증 또는 동기화 대기 상태를 읽는다."""
        path = self._path(checksum)
        if not path.is_file() or path.is_symlink():
            return None
        return json.loads(path.read_text(encoding="utf-8"))

    def notify(self, metadata):
        """해시·건수·크기만 받고 원본 내용은 HTTP로 받지 않는다."""
        if not isinstance(metadata, dict) or set(metadata) != {"sha256", "cases", "size"}:
            raise TransferError("전송 알림 형식이 올바르지 않습니다.")
        checksum, cases, size = metadata["sha256"], metadata["cases"], metadata["size"]
        if (not isinstance(checksum, str) or not HASH.fullmatch(checksum)
                or type(cases) is not int or cases < 1 or type(size) is not int or size < 1 or size > MAX_BYTES):
            raise TransferError("전송 알림 값이 올바르지 않습니다.")
        with self.lock:
            existing = self.status(checksum)
            if existing:
                if existing["cases"] != cases or existing["size"] != size:
                    raise TransferError("같은 해시의 전송 알림 값이 다릅니다.")
                return existing
            record = {"sha256": checksum, "cases": cases, "size": size, "status": "WAITING_FOR_FILE"}
            write_json_atomic(self._path(checksum), record)
            self.log_event(checksum, "NOTICE_ACCEPTED", "전송 알림 접수 · 파일 동기화를 확인합니다.", cases=cases, size=size)
            return record

    def tick(self, checksum):
        """동기화가 끝난 파일만 임시 수신함을 거쳐 검증 완료로 확정한다."""
        with self.lock:
            record = self.status(checksum)
            if not record or record["status"] in {"VERIFIED", "REJECTED"}:
                return record
            shared = self.shared_dir / f"{checksum}.jsonl"
            if not shared.is_file() or shared.is_symlink():
                self.log_event(checksum, "WAITING_FOR_FILE", "공유 폴더의 파일 도착을 기다립니다.")
                return record
            try:
                actual, size = content_hash(shared)
            except (OSError, TransferError):
                self.log_event(checksum, "FILE_READ_RETRY", "파일을 읽지 못해 다음 확인 때 재시도합니다.", level=logging.WARNING)
                return record
            if actual != checksum or size != record["size"]:
                self.log_event(checksum, "WAITING_FOR_SYNC", "파일 동기화가 끝나지 않아 해시·크기 일치를 기다립니다.")
                return record
            self.log_event(checksum, "FILE_ARRIVED", "파일 도착 확인 · 학습 데이터 검증을 시작합니다.", cases=record["cases"], size=size)

            descriptor, temporary_name = tempfile.mkstemp(prefix=f".{checksum}-", dir=self.staging)
            temporary = Path(temporary_name)
            staged = self.staging / f"{checksum}.jsonl.part"
            try:
                with os.fdopen(descriptor, "wb") as target, shared.open("rb") as source:
                    os.fchmod(target.fileno(), 0o600)
                    shutil.copyfileobj(source, target, length=1024 * 1024)
                    target.flush()
                    os.fsync(target.fileno())
                if content_hash(temporary)[0] != checksum:
                    self.log_event(checksum, "STAGING_RETRY", "복사 중 파일이 달라져 다음 확인 때 재시도합니다.", level=logging.WARNING)
                    return record
                os.replace(temporary, staged)
                if checked_cases(staged) != record["cases"]:
                    raise TransferError("수신 사례 수가 발신 알림과 다릅니다.")
                receive(staged, self.inbox)
                record = {**record, "status": "VERIFIED"}
            except (DatasetError, TransferError, UnicodeError) as error:
                record = {**record, "status": "REJECTED"}
                code = getattr(error, "code", "CASE_CONTRACT_INVALID")
                if code not in {"GROUNDING_MISMATCH", "CASE_RESPONSE_INVALID", "CASE_NOT_SANITIZED", "CASE_CONTRACT_INVALID"}:
                    code = "CASE_CONTRACT_INVALID"
                self.log_event(checksum, code, "학습 데이터 검증 실패 · 자료를 학습에 사용하지 않습니다.", level=logging.WARNING)
            except OSError:
                self.log_event(checksum, "INBOX_WRITE_RETRY", "수신함 저장을 완료하지 못해 다음 확인 때 재시도합니다.", level=logging.WARNING)
                return record
            finally:
                temporary.unlink(missing_ok=True)
            write_json_atomic(self._path(checksum), record)
            if record["status"] == "VERIFIED":
                self.log_event(checksum, "VERIFIED", "수신·검증 완료 · 데이터셋 준비 대기입니다. 모델 학습은 시작하지 않았습니다.", cases=record["cases"], size=record["size"])
            return record

    def tick_all(self):
        """화면을 닫아도 대기 중인 전송을 다시 확인한다."""
        for path in self.state_dir.glob("[0-9a-f]*.json"):
            if HASH.fullmatch(path.stem):
                try:
                    self.tick(path.stem)
                except (OSError, ValueError):
                    self.log_event(path.stem, "STATE_RETRY", "수신 기록을 확인하지 못해 다음 확인 때 재시도합니다.", level=logging.WARNING)
                    continue

    def intake_status(self):
        """검증된 수신 사례가 없으면 대기하고 있으면 데이터셋 준비 대상으로 표시한다."""
        verified = 0
        cases = 0
        with self.lock:
            for path in self.state_dir.glob("*.json"):
                if not HASH.fullmatch(path.stem):
                    continue
                record = self.status(path.stem)
                package = self.inbox / f"{path.stem}.jsonl"
                if record and record.get("status") == "VERIFIED" and package.is_file() and not package.is_symlink():
                    verified += 1
                    cases += record["cases"]
        return {"status": "READY_FOR_DATASET" if verified else "WAITING_FOR_DATA",
                "files": verified, "cases": cases}


def make_handler(store, secret, jobs=None):
    """기존 수신과 새 학습 작업 API 모두 같은 서명 인증으로 보호한다."""
    class Handler(BaseHTTPRequestHandler):
        def _send(self, status, value):
            body = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _authorized(self, body):
            return verify_signature(secret, self.command, self.path, body, self.headers)

        def _job_error(self, error):
            if isinstance(error, TrainingJobError):
                self._send(error.http_status, {"code": error.code, "message": error.message})
            else:
                self._send(500, {"code": "TRAINING_INTERNAL_ERROR", "message": "학습 작업을 처리하지 못했습니다. 서버 상태를 확인하세요."})
            logger.warning("학습 작업 API 처리 실패 code=%s", error.code if isinstance(error, TrainingJobError) else "TRAINING_INTERNAL_ERROR")

        def do_POST(self):
            cancel = CANCEL_PATH.fullmatch(self.path)
            job_request = self.path == JOBS_PATH or cancel is not None
            if self.path != "/api/training/transfers" and not job_request:
                self._send(404, {"code": "NOT_FOUND"})
                return
            try:
                length = int(self.headers.get("Content-Length", ""))
            except ValueError:
                length = 0
            if length < 1 or length > 4096:
                self._send(413, {"code": "INVALID_SIZE"})
                return
            body = self.rfile.read(length)
            if not self._authorized(body):
                store.log_event(None, "NOTICE_AUTH_REJECTED", "수신 알림 인증 실패 · 두 서버의 전송 키와 시각을 확인해 주세요.", level=logging.WARNING)
                self._send(401, {"code": "UNAUTHORIZED"})
                return
            if job_request:
                if jobs is None:
                    self._send(503, {"code": "TRAINING_UNAVAILABLE", "message": "학습 실행 설정이 준비되지 않았습니다."})
                    return
                try:
                    value = strict_json(body.decode("utf-8"))
                except (ValueError, TypeError, UnicodeError):
                    self._send(422, {"code": "INVALID_JOB_REQUEST", "message": "학습 요청의 JSON 형식을 확인하세요."})
                    return
                try:
                    value = jobs.cancel(cancel.group(1), value) if cancel else jobs.submit(value)
                    self._send(200 if cancel else 202, value)
                except Exception as error:
                    self._job_error(error)
                return
            try:
                value = store.notify(json.loads(body))
            except (ValueError, TypeError, UnicodeError):
                store.log_event(None, "INVALID_NOTIFICATION", "수신 알림 형식이 올바르지 않습니다.", level=logging.WARNING)
                self._send(422, {"code": "INVALID_NOTIFICATION"})
                return
            self._send(202, value)

        def do_GET(self):
            job = JOB_PATH.fullmatch(self.path)
            if self.path == JOBS_PATH or job:
                if not self._authorized(b""):
                    self._send(401, {"code": "UNAUTHORIZED"})
                    return
                try:
                    value = jobs.get_job(job.group(1)) if job and jobs else (
                        jobs.list_jobs() if jobs else {"jobs": [], "limit": 100, "truncated": False})
                    if job and jobs is None:
                        value = None
                    self._send(200 if value is not None else 404, value if value is not None else {"code": "NOT_FOUND", "message": "학습 작업을 찾을 수 없습니다."})
                except Exception as error:
                    self._job_error(error)
                return
            if self.path == CAPABILITIES_PATH:
                if not self._authorized(b""):
                    self._send(401, {"code": "UNAUTHORIZED"})
                    return
                try:
                    execution = jobs.capabilities() if jobs else {
                        "trainingExecution": False, "trainingExecutionReason": "학습 실행 설정이 준비되지 않았습니다."}
                    self._send(200, {"validationVersion": 2, "features": list(VALIDATION_FEATURES), **execution})
                except Exception as error:
                    self._job_error(error)
                return
            if self.path == INTAKE_PATH:
                if not self._authorized(b""):
                    self._send(401, {"code": "UNAUTHORIZED"})
                    return
                self._send(200, store.intake_status())
                return
            match = STATUS_PATH.fullmatch(self.path)
            if not match:
                self._send(404, {"code": "NOT_FOUND"})
                return
            if not self._authorized(b""):
                self._send(401, {"code": "UNAUTHORIZED"})
                return
            value = store.status(match.group(1))
            self._send(200 if value else 404, value or {"code": "NOT_FOUND"})

        def log_message(self, *_):
            """요청 경로나 헤더가 터미널 로그에 섞이지 않게 한다."""

    return Handler


def serve(store, secret, host, port):
    """수신 API와 주기적 재확인을 함께 실행한다."""
    jobs = TrainingJobStore(store, os.environ.get("DOENAAI_TRAINING_MODEL_DIR"))
    try:
        server = ThreadingHTTPServer((host, port), make_handler(store, secret, jobs))
    except Exception:
        jobs.close()
        raise
    stopping = threading.Event()

    def check_pending():
        while not stopping.wait(5):
            store.tick_all()

    worker = threading.Thread(target=check_pending, name="training-receiver", daemon=True)
    worker.start()
    try:
        logger.info("AI 학습 서버 수신 서비스 시작 port=%s validation_version=2", port)
        try:
            initial = store.intake_status()
            logger.info("수신함 확인 status=%s files=%s cases=%s · 새 전송 알림을 기다립니다.", initial["status"], initial["files"], initial["cases"])
        except (OSError, ValueError):
            logger.warning("수신함 상태 확인 대기 code=INTAKE_READ_RETRY")
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        logger.info("수신 서비스 종료 요청을 받았습니다.")
    finally:
        stopping.set()
        server.server_close()
        worker.join(timeout=2)
        jobs.close()
        logger.info("수신 서비스가 종료되었습니다. 저장된 수신 기록은 유지됩니다.")


def main():
    """AI 학습 서버에서 명시적으로 지정한 공유 폴더만 관찰한다."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8011)
    parser.add_argument("--env-file", type=Path, help="기본 .env 대신 읽을 설정 파일")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s [AI 학습 서버] %(message)s")
    env_file = args.env_file or ROOT / ".env"
    if args.env_file is None and not env_file.exists():
        env_file = ROOT.parent / ".env"
    try:
        loaded = load_receiver_env(env_file, required=args.env_file is not None)
    except ValueError as error:
        parser.exit(2, f"설정 확인 실패: {error}\n")
    logger.info("수신 설정 준비 env_file=%s · 설정값과 비밀키는 출력하지 않습니다.", "loaded" if loaded else "not_found")
    secret = os.environ.get("DOENAAI_TRAINING_TRANSFER_KEY", "")
    shared = os.environ.get("DOENAAI_TRAINING_SHARED_DIR", "")
    if len(secret) < 32 or not shared or not Path(shared).is_absolute():
        parser.exit(2, "수신 실패: 전송 키와 공유 폴더의 절대 경로를 설정하세요.\n")
    try:
        serve(ReceiverStore(shared), secret, args.host, args.port)
    except (OSError, ValueError):
        parser.exit(2, "수신 서버 시작 실패: 포트 사용 여부와 수신 폴더의 접근 권한을 확인해 주세요.\n")


if __name__ == "__main__":
    main()
