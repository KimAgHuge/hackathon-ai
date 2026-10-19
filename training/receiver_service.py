"""AI 학습 서버에서 공유 폴더의 학습 사례를 검증하고 수신 상태를 반환한다."""

import argparse
import json
import os
import re
import shutil
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from dataset_contract import DatasetError
from shared_transfer import HASH, verify_signature, write_json_atomic
from transfer_cases import MAX_BYTES, TransferError, checked_cases, content_hash, private_directory, receive


ROOT = Path(__file__).resolve().parent
DEFAULT_STATE = ROOT / "datasets/receiver-state"
DEFAULT_STAGING = ROOT / "datasets/transfer-staging"
DEFAULT_INBOX = ROOT / "datasets/incoming"
STATUS_PATH = re.compile(r"/api/training/transfers/([0-9a-f]{64})\Z")
INTAKE_PATH = "/api/training/intake"


class ReceiverStore:
    """동기화 지연·재시작을 견디도록 알림과 수신증을 디스크에 보관한다."""

    def __init__(self, shared_dir, state_dir=DEFAULT_STATE, staging=DEFAULT_STAGING, inbox=DEFAULT_INBOX):
        self.shared_dir = Path(shared_dir)
        self.state_dir = private_directory(state_dir)
        self.staging = private_directory(staging)
        self.inbox = private_directory(inbox)
        self.lock = threading.RLock()

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
            return record

    def tick(self, checksum):
        """동기화가 끝난 파일만 임시 수신함을 거쳐 검증 완료로 확정한다."""
        with self.lock:
            record = self.status(checksum)
            if not record or record["status"] in {"VERIFIED", "REJECTED"}:
                return record
            shared = self.shared_dir / f"{checksum}.jsonl"
            if not shared.is_file() or shared.is_symlink():
                return record
            try:
                actual, size = content_hash(shared)
            except (OSError, TransferError):
                return record
            if actual != checksum or size != record["size"]:
                return record

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
                    return record
                os.replace(temporary, staged)
                if checked_cases(staged) != record["cases"]:
                    raise TransferError("수신 사례 수가 발신 알림과 다릅니다.")
                receive(staged, self.inbox)
                record = {**record, "status": "VERIFIED"}
            except (DatasetError, TransferError, UnicodeError):
                record = {**record, "status": "REJECTED"}
            except OSError:
                return record
            finally:
                temporary.unlink(missing_ok=True)
            write_json_atomic(self._path(checksum), record)
            return record

    def tick_all(self):
        """화면을 닫아도 대기 중인 전송을 다시 확인한다."""
        for path in self.state_dir.glob("[0-9a-f]*.json"):
            if HASH.fullmatch(path.stem):
                try:
                    self.tick(path.stem)
                except (OSError, ValueError):
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


def make_handler(store, secret):
    """서명된 알림과 수신 상태 조회만 노출한다."""
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

        def do_POST(self):
            if self.path != "/api/training/transfers":
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
                self._send(401, {"code": "UNAUTHORIZED"})
                return
            try:
                value = store.notify(json.loads(body))
            except (ValueError, TypeError, UnicodeError):
                self._send(422, {"code": "INVALID_NOTIFICATION"})
                return
            self._send(202, value)

        def do_GET(self):
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
    server = ThreadingHTTPServer((host, port), make_handler(store, secret))
    stopping = threading.Event()

    def check_pending():
        while not stopping.wait(5):
            store.tick_all()

    worker = threading.Thread(target=check_pending, name="training-receiver", daemon=True)
    worker.start()
    try:
        server.serve_forever(poll_interval=0.5)
    finally:
        stopping.set()
        server.server_close()
        worker.join(timeout=2)


def main():
    """AI 학습 서버에서 명시적으로 지정한 공유 폴더만 관찰한다."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8011)
    args = parser.parse_args()
    secret = os.environ.get("DOENAAI_TRAINING_TRANSFER_KEY", "")
    shared = os.environ.get("DOENAAI_TRAINING_SHARED_DIR", "")
    if len(secret) < 32 or not shared or not Path(shared).is_absolute():
        parser.exit(2, "수신 실패: 전송 키와 공유 폴더의 절대 경로를 설정하세요.\n")
    serve(ReceiverStore(shared), secret, args.host, args.port)


if __name__ == "__main__":
    main()
