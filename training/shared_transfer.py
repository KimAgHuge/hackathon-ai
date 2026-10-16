"""검토된 한 건의 학습 사례를 공유 폴더에 원자적으로 게시한다."""

import fcntl
import hashlib
import hmac
import json
import os
import re
import shutil
import tempfile
import time
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from uuid import UUID

from dataset_contract import validate_case
from transfer_cases import MAX_BYTES, TransferError, content_hash, private_directory, stage, sync_directory


HASH = re.compile(r"[0-9a-f]{64}\Z")


def canonical_bytes(value):
    """같은 사례가 재전송될 때 동일한 파일 해시를 갖게 한다."""
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")


def write_json_atomic(path, value):
    """상태 파일을 임시 파일에 기록한 뒤 원자적으로 교체한다."""
    path = Path(path)
    private_directory(path.parent)
    descriptor, temporary_name = tempfile.mkstemp(prefix=".state-", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as target:
            os.fchmod(target.fileno(), 0o600)
            target.write(canonical_bytes(value))
            target.flush()
            os.fsync(target.fileno())
        os.replace(temporary, path)
        sync_directory(path.parent)
    finally:
        temporary.unlink(missing_ok=True)


@contextmanager
def locked_directory(path):
    """같은 분석 작업을 동시에 게시해 서로 다른 파일을 만들지 않도록 잠근다."""
    directory = private_directory(path)
    descriptor = os.open(directory / ".lock", os.O_CREAT | os.O_RDWR, 0o600)
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        yield directory
    finally:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)


def read_job_status(job_id, status_dir):
    """분석 작업에 연결된 전송 상태만 반환한다."""
    job_id = str(UUID(str(job_id)))
    path = Path(status_dir) / f"{job_id}.json"
    if not path.is_file() or path.is_symlink():
        return None
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("jobId") != job_id or not HASH.fullmatch(value.get("sha256", "")):
        raise TransferError("저장된 전송 상태가 올바르지 않습니다.")
    return value


def publish_case(job_id, case, shared_dir, *, outbox, status_dir):
    """계약을 검증한 사례만 outbox와 공유 폴더에 해시 이름으로 남긴다."""
    job_id = str(UUID(str(job_id)))
    if not isinstance(case, dict) or case.get("caseId") != f"analysis-{job_id}":
        raise TransferError("승인 분석 작업과 학습 사례 ID가 일치하지 않습니다.")
    validate_case(case)
    payload = canonical_bytes(case)
    if len(payload) > MAX_BYTES:
        raise TransferError("사례 파일이 512 MiB 제한을 초과했습니다.")
    checksum = hashlib.sha256(payload).hexdigest()
    with locked_directory(status_dir):
        existing = read_job_status(job_id, status_dir)
        if existing and existing["sha256"] != checksum:
            raise TransferError("이 분석 작업에서 이미 다른 학습 사례를 전송했습니다.")

        outbox = private_directory(outbox)
        descriptor, temporary_name = tempfile.mkstemp(prefix=".reviewed-", dir=outbox)
        temporary = Path(temporary_name)
        try:
            with os.fdopen(descriptor, "wb") as target:
                os.fchmod(target.fileno(), 0o600)
                target.write(payload)
                target.flush()
                os.fsync(target.fileno())
            package = stage(temporary, outbox)
        finally:
            temporary.unlink(missing_ok=True)

        shared = Path(shared_dir)
        if shared.is_symlink() or not shared.is_dir():
            raise OSError("설정한 공유 폴더가 연결되어 있지 않습니다.")
        destination = shared / f"{checksum}.jsonl"
        if destination.exists():
            if destination.is_symlink() or content_hash(destination)[0] != checksum:
                raise TransferError("공유 폴더에 같은 이름의 다른 파일이 있습니다.")
        else:
            descriptor, temporary_name = tempfile.mkstemp(prefix=f".{checksum}-", suffix=".uploading", dir=shared)
            uploading = Path(temporary_name)
            try:
                with os.fdopen(descriptor, "wb") as target, Path(package["package"]).open("rb") as source:
                    os.fchmod(target.fileno(), 0o600)
                    shutil.copyfileobj(source, target, length=1024 * 1024)
                    target.flush()
                    os.fsync(target.fileno())
                if content_hash(uploading)[0] != checksum:
                    raise TransferError("공유 폴더에 복사한 파일의 해시가 다릅니다.")
                os.replace(uploading, destination)
                sync_directory(shared)
            finally:
                uploading.unlink(missing_ok=True)

        status = existing or {
            "jobId": job_id,
            "sha256": checksum,
            "cases": package["cases"],
            "size": len(payload),
            "status": "WAITING_FOR_RECEIVER",
            "createdAt": datetime.now(timezone.utc).isoformat(),
        }
        write_json_atomic(Path(status_dir) / f"{job_id}.json", status)
        return status


def signed_headers(secret, method, path, body=b"", timestamp=None):
    """파일 내용 없이 전송 알림과 수신 확인 요청에 서명한다."""
    timestamp = int(time.time()) if timestamp is None else int(timestamp)
    message = f"{timestamp}\n{method.upper()}\n{path}\n".encode() + body
    signature = hmac.new(secret.encode(), message, hashlib.sha256).hexdigest()
    return {"X-Transfer-Timestamp": str(timestamp), "X-Transfer-Signature": signature}


def verify_signature(secret, method, path, body, headers, now=None):
    """짧은 시간 창과 일정 시간 비교로 재전송·위조 요청을 거른다."""
    try:
        timestamp = int(headers.get("X-Transfer-Timestamp", ""))
    except (TypeError, ValueError):
        return False
    now = time.time() if now is None else now
    if abs(now - timestamp) > 300:
        return False
    supplied = headers.get("X-Transfer-Signature", "")
    expected = signed_headers(secret, method, path, body, timestamp)["X-Transfer-Signature"]
    return bool(re.fullmatch(r"[0-9a-f]{64}", supplied)) and hmac.compare_digest(supplied, expected)
