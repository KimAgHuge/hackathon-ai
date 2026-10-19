"""검토된 학습 사례를 공유 폴더에 게시하고 AI 학습 서버의 수신증을 확인한다."""

import json
import sys
from contextlib import nullcontext
from pathlib import Path
from uuid import UUID
from urllib.parse import urlsplit

import httpx

from app.config import Settings

TRAINING_ROOT = Path(__file__).resolve().parents[3] / "training"
sys.path.insert(0, str(TRAINING_ROOT))

from dataset_contract import DatasetError  # noqa: E402
from shared_transfer import (  # noqa: E402
    locked_directory, publish_case, read_job_status, signed_headers, write_json_atomic,
)
from transfer_cases import TransferError  # noqa: E402


class TransferConfigurationError(TransferError):
    """공유 폴더·수신 주소·서명 키의 설정이 누락됐을 때 발생한다."""


class TrainingTransferService:
    """전송 요청이 끊겨도 같은 분석 작업과 해시로 상태를 재개한다."""

    def __init__(self, settings: Settings, *, outbox=None, status_dir=None, client=None):
        self.settings = settings
        self.outbox = outbox or TRAINING_ROOT / "datasets/outbox"
        self.status_dir = status_dir or TRAINING_ROOT / "datasets/transfer-status"
        self.client = client

    def _configured(self):
        shared = self.settings.training_shared_dir
        receiver = self.settings.training_receiver_url.rstrip("/")
        secret = self.settings.training_transfer_key.get_secret_value()
        parsed = urlsplit(receiver)
        if (not shared or not Path(shared).is_absolute() or parsed.scheme not in {"http", "https"}
                or not parsed.hostname or parsed.username or parsed.password or parsed.path not in {"", "/"}
                or parsed.query or parsed.fragment or len(secret) < 32):
            raise TransferConfigurationError("학습 전송 공유 폴더·AI 학습 서버 주소·전송 키를 먼저 설정하세요.")
        return shared, receiver, secret

    def publish(self, job_id, case):
        """사람이 검토한 사례만 게시하고 AI 학습 서버에 수신을 요청한다."""
        shared, _, _ = self._configured()
        status = publish_case(job_id, case, shared, outbox=self.outbox, status_dir=self.status_dir)
        return self.advance(status)

    def by_job(self, job_id):
        """저장된 작업을 다시 열어 통지가 실패했으면 재시도한다."""
        self._configured()
        status = read_job_status(str(UUID(str(job_id))), self.status_dir)
        return self.advance(status) if status else None

    def retry_pending(self):
        """화면이 닫혀도 미완료 알림을 반복해 AI 학습 서버에 전달한다."""
        if not Path(self.status_dir).is_dir():
            return
        for path in Path(self.status_dir).glob("*.json"):
            try:
                status = read_job_status(path.stem, self.status_dir)
                if status and status["status"] not in {"VERIFIED", "REJECTED"}:
                    self.advance(status)
            except (OSError, ValueError):
                continue

    def advance(self, status):
        """알림 접수와 실제 파일 검증 완료를 서로 다른 상태로 표시한다."""
        if status["status"] in {"VERIFIED", "REJECTED"}:
            return status
        _, receiver, secret = self._configured()
        notice_path = "/api/training/transfers"
        body = json.dumps({key: status[key] for key in ("sha256", "cases", "size")},
                          ensure_ascii=False, separators=(",", ":")).encode()
        try:
            context = nullcontext(self.client) if self.client else httpx.Client(timeout=5, follow_redirects=False, trust_env=False)
            with context as client:
                notified = client.post(receiver + notice_path, content=body,
                                       headers={"Content-Type": "application/json", **signed_headers(secret, "POST", notice_path, body)})
                if notified.status_code != 202:
                    return status
                status_path = notice_path + "/" + status["sha256"]
                checked = client.get(receiver + status_path,
                                     headers=signed_headers(secret, "GET", status_path))
                if checked.status_code != 200:
                    next_status = "WAITING_FOR_SYNC"
                else:
                    receipt = checked.json()
                    if receipt.get("sha256") != status["sha256"] or receipt.get("cases") != status["cases"] or receipt.get("size") != status["size"]:
                        next_status = "WAITING_FOR_RECEIVER"
                    elif receipt.get("status") in {"VERIFIED", "REJECTED"}:
                        next_status = receipt["status"]
                    else:
                        next_status = "WAITING_FOR_SYNC"
        except (httpx.HTTPError, ValueError, TypeError):
            next_status = "WAITING_FOR_RECEIVER"
        if next_status != status["status"]:
            status = {**status, "status": next_status}
            with locked_directory(self.status_dir):
                write_json_atomic(Path(self.status_dir) / f"{status['jobId']}.json", status)
        return status
