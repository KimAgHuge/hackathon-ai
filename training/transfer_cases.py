"""검토된 JSONL을 원본 보존·재시도·해시 검증 후 맥북에 확정한다."""

import argparse
import hashlib
import json
import os
import re
import shlex
import subprocess
import tempfile
import time
from pathlib import Path, PurePosixPath

from dataset_contract import DatasetError, digest, read_jsonl, validate_case


ROOT = Path(__file__).resolve().parent
DEFAULT_OUTBOX = ROOT / "datasets/outbox"
DEFAULT_INBOX = ROOT / "datasets/incoming"
HASHED_NAME = re.compile(r"([0-9a-f]{64})\.jsonl(?:\.part)?\Z")
REMOTE = re.compile(r"[A-Za-z0-9_][A-Za-z0-9._-]*@[A-Za-z0-9][A-Za-z0-9.-]*\Z")
REMOTE_PATH = re.compile(r"/[A-Za-z0-9_./-]+\Z")
MAX_BYTES = 512 * 1024 * 1024


class TransferError(ValueError):
    """원본 내용이나 SSH 상세 오류를 노출하지 않는 전송 실패다."""


def content_hash(path):
    """파일 크기를 제한하면서 SHA-256과 바이트 수를 계산한다."""
    checksum = hashlib.sha256()
    size = 0
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            size += len(chunk)
            if size > MAX_BYTES:
                raise TransferError("사례 파일이 512 MiB 제한을 초과했습니다.")
            checksum.update(chunk)
    return checksum.hexdigest(), size


def checked_cases(path):
    """학습 계약과 중복 ID·입력을 확인하고 사례 수만 반환한다."""
    records = read_jsonl(path)
    identifiers, inputs = set(), set()
    for record in records:
        validate_case(record)
        case_id = record["caseId"]
        input_hash = digest({key: value for key, value in record["request"].items() if key != "projectId"})
        if case_id in identifiers or input_hash in inputs:
            raise TransferError("사례 ID 또는 분석 입력이 중복됐습니다.")
        identifiers.add(case_id)
        inputs.add(input_hash)
    return len(records)


def private_directory(path):
    """전송 임시 자료가 다른 로컬 사용자에게 노출되지 않도록 디렉터리를 만든다."""
    path = Path(path)
    if path.is_symlink():
        raise TransferError("전송 폴더는 심볼릭 링크일 수 없습니다.")
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    path.chmod(0o700)
    return path


def sync_directory(path):
    """원자적 이름 변경까지 디스크에 반영해 전원 중단 후에도 확정 파일을 찾게 한다."""
    descriptor = os.open(path, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def stage(source, outbox=DEFAULT_OUTBOX):
    """입력 파일을 검증해 SHA-256 이름의 재전송 가능한 사본을 원자적으로 보관한다."""
    source = Path(source)
    if not source.is_file() or source.is_symlink():
        raise TransferError("승인 사례 입력 파일을 확인할 수 없습니다.")
    outbox = private_directory(outbox)
    descriptor, temporary_name = tempfile.mkstemp(prefix=".cases-", dir=outbox)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as target, source.open("rb") as original:
            os.fchmod(target.fileno(), 0o600)
            size = 0
            for chunk in iter(lambda: original.read(1024 * 1024), b""):
                size += len(chunk)
                if size > MAX_BYTES:
                    raise TransferError("사례 파일이 512 MiB 제한을 초과했습니다.")
                target.write(chunk)
            target.flush()
            os.fsync(target.fileno())
        cases = checked_cases(temporary)
        checksum, _ = content_hash(temporary)
        package = outbox / f"{checksum}.jsonl"
        if package.exists():
            if package.is_symlink() or content_hash(package)[0] != checksum:
                raise TransferError("기존 전송 사본의 무결성이 맞지 않습니다.")
        else:
            os.replace(temporary, package)
            sync_directory(outbox)
        return {"sha256": checksum, "cases": cases, "package": str(package)}
    finally:
        temporary.unlink(missing_ok=True)


def receive(staged, inbox=DEFAULT_INBOX):
    """부분 파일을 검사한 뒤에만 수신함에 원자적으로 확정한다. 재호출은 안전하다."""
    staged = Path(staged)
    match = HASHED_NAME.fullmatch(staged.name)
    if not match or not staged.name.endswith(".part") or staged.is_symlink() or not staged.is_file():
        raise TransferError("수신 임시 파일 이름 또는 형식이 올바르지 않습니다.")
    expected = match.group(1)
    actual, _ = content_hash(staged)
    if actual != expected:
        raise TransferError("수신 파일의 SHA-256이 발신 파일과 다릅니다. 임시 파일은 보존했습니다.")
    cases = checked_cases(staged)
    inbox = private_directory(inbox)
    destination = inbox / f"{expected}.jsonl"
    if destination.exists():
        if destination.is_symlink() or content_hash(destination)[0] != expected:
            raise TransferError("이미 수신한 파일의 무결성이 맞지 않습니다.")
        status = "already_received"
        staged.unlink()
    else:
        with staged.open("rb") as source:
            os.fsync(source.fileno())
        os.replace(staged, destination)
        sync_directory(inbox)
        status = "received"
    return {"sha256": expected, "cases": cases, "status": status, "file": str(destination)}


def remote_paths(repo):
    """SSH 원격 셸에 전달할 경로를 제한해 명령 삽입을 막는다."""
    if not REMOTE_PATH.fullmatch(repo) or ".." in PurePosixPath(repo).parts:
        raise TransferError("맥북 저장소 경로는 공백 없는 절대 경로여야 합니다.")
    base = repo.rstrip("/") + "/training"
    return (base + "/datasets/transfer-staging", base + "/datasets/incoming",
            base + "/.venv/bin/python", base + "/transfer_cases.py")


def send(package, remote, remote_repo, attempts=5, timeout=300, runner=subprocess.run, sleeper=time.sleep):
    """SSH·rsync 전송을 재시도하고 맥북 검증 수신증의 해시가 맞을 때만 성공한다."""
    package = Path(package)
    match = HASHED_NAME.fullmatch(package.name)
    if not match or package.name.endswith(".part") or package.is_symlink() or not package.is_file():
        raise TransferError("먼저 stage 명령으로 만든 전송 사본을 지정해 주세요.")
    checksum = match.group(1)
    if content_hash(package)[0] != checksum:
        raise TransferError("전송 사본의 SHA-256이 파일 이름과 다릅니다.")
    cases = checked_cases(package)
    if not REMOTE.fullmatch(remote):
        raise TransferError("SSH 대상은 사용자@호스트 형식이어야 합니다.")
    if attempts < 1 or attempts > 20 or timeout < 10:
        raise TransferError("재시도 횟수 또는 명령 제한 시간이 올바르지 않습니다.")
    staging, inbox, python, script = remote_paths(remote_repo)
    staged = staging + f"/{checksum}.jsonl.part"
    ssh = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", remote]
    setup = "umask 077; mkdir -p " + shlex.join([staging, inbox]) + "; chmod 700 " + shlex.join([staging, inbox])
    commands = [ssh + [setup],
                ["rsync", "-a", "-c", "--partial", "-e", "ssh -o BatchMode=yes -o ConnectTimeout=10",
                 str(package), f"{remote}:{staged}"],
                ssh + [shlex.join([python, script, "receive", "--staged", staged, "--inbox", inbox])]]
    for attempt in range(1, attempts + 1):
        try:
            for command in commands:
                result = runner(command, capture_output=True, text=True, timeout=timeout)
                if result.returncode != 0:
                    raise TransferError("전송 또는 맥북 검증 단계가 실패했습니다.")
            receipt_lines = [line for line in result.stdout.splitlines() if line.strip()]
            if not receipt_lines:
                raise TransferError("맥북의 수신 확인을 받지 못했습니다.")
            receipt = json.loads(receipt_lines[-1])
            if receipt.get("sha256") != checksum or receipt.get("cases") != cases or receipt.get("status") not in {"received", "already_received"}:
                raise TransferError("맥북의 수신 확인 값이 발신 파일과 다릅니다.")
            return {"sha256": checksum, "cases": cases, "status": receipt["status"], "attempts": attempt}
        except (OSError, subprocess.TimeoutExpired, json.JSONDecodeError, TransferError):
            if attempt == attempts:
                raise TransferError("전송 확인에 실패했습니다. 원본과 전송 사본은 보존됐으며 같은 명령을 다시 실행할 수 있습니다.") from None
            sleeper(min(2 ** (attempt - 1), 30))


def main():
    """원본 생성·전송·수신을 분리해 통신 중단 뒤 같은 파일로 재시작할 수 있게 한다."""
    parser = argparse.ArgumentParser(description=__doc__)
    subcommands = parser.add_subparsers(dest="action", required=True)
    stage_parser = subcommands.add_parser("stage", help="승인 JSONL을 검증하고 전송 사본을 보관")
    stage_parser.add_argument("--input", required=True)
    stage_parser.add_argument("--outbox", default=str(DEFAULT_OUTBOX))
    send_parser = subcommands.add_parser("send", help="SSH로 재전송하고 맥북 수신 확인")
    send_parser.add_argument("--package", required=True)
    send_parser.add_argument("--remote", required=True, help="맥북 SSH 사용자@호스트")
    send_parser.add_argument("--remote-repo", required=True, help="맥북의 저장소 절대 경로")
    send_parser.add_argument("--attempts", type=int, default=5)
    send_parser.add_argument("--timeout", type=int, default=300)
    receive_parser = subcommands.add_parser("receive", help="수신 임시 파일 검사 후 원자적으로 확정")
    receive_parser.add_argument("--staged", required=True)
    receive_parser.add_argument("--inbox", default=str(DEFAULT_INBOX))
    args = parser.parse_args()
    try:
        if args.action == "stage":
            result = stage(args.input, args.outbox)
        elif args.action == "send":
            result = send(args.package, args.remote, args.remote_repo, args.attempts, args.timeout)
        else:
            result = receive(args.staged, args.inbox)
    except (DatasetError, TransferError) as error:
        parser.exit(1, f"전송 실패: {error}\n")
    except (OSError, UnicodeError):
        parser.exit(1, "전송 실패: 파일 또는 디렉터리 상태를 확인하세요.\n")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
