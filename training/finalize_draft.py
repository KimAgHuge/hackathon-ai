"""사람이 보완한 학습 사례 초안만 검사해 JSONL 한 건으로 확정한다."""

import argparse
import json
import os
import tempfile
from pathlib import Path

from dataset_contract import DatasetError, strict_json, validate_case


def finalize(source, destination):
    """원본 초안은 보존하고, 유효한 사례만 기존 파일을 덮지 않고 저장한다."""
    source, destination = Path(source), Path(destination)
    try:
        wrapper = strict_json(source.read_text(encoding="utf-8"))
    except (UnicodeError, OSError):
        raise DatasetError("초안 파일을 UTF-8로 읽을 수 없습니다.") from None
    except json.JSONDecodeError:
        raise DatasetError("초안의 JSON 문법이 올바르지 않습니다.") from None
    if not isinstance(wrapper, dict) or set(wrapper) != {"caseDraft", "reviewRequired"}:
        raise DatasetError("다운로드한 학습 사례 초안 형식이 필요합니다.")
    record = wrapper["caseDraft"]
    if not isinstance(record, dict):
        raise DatasetError("caseDraft는 JSON 객체여야 합니다.")
    validate_case(record)
    if destination.suffix != ".jsonl":
        raise DatasetError("출력 파일 확장자는 .jsonl이어야 합니다.")
    destination.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=".reviewed-", dir=destination.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            os.fchmod(output.fileno(), 0o600)
            output.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")
            output.flush()
            os.fsync(output.fileno())
        try:
            os.link(temporary, destination)
        except FileExistsError:
            raise DatasetError("출력 파일이 이미 있습니다. 기존 승인 사례를 덮어쓰지 않았습니다.") from None
        directory = os.open(destination.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        temporary.unlink(missing_ok=True)
    return {"caseId": record["caseId"], "file": str(destination)}


def main():
    """검토 완료된 초안을 출력 파일 하나로 확정한다."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="사람이 검토·보완한 초안 JSON")
    parser.add_argument("--output", required=True, help="새로 만들 JSONL 파일")
    args = parser.parse_args()
    try:
        result = finalize(args.input, args.output)
    except DatasetError as error:
        parser.exit(1, f"학습 사례 확정 실패: {error}\n")
    except OSError:
        parser.exit(1, "학습 사례 확정 실패: 출력 경로를 확인하세요.\n")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
