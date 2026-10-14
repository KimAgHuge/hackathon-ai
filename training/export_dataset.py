"""승인 사례를 검사하고 프로젝트별로 나눠 MLX 학습·검증·시험 JSONL을 만든다."""

import argparse
import json
import shutil
import tempfile
from pathlib import Path

from dataset_contract import DatasetError, digest, messages_for, prompt_contract, read_jsonl, strict_json, validate_case


def prepare(records, split_plan):
    """명시적인 프로젝트 배정으로 학습·검증·시험 간 누출을 차단한다."""
    if not isinstance(split_plan, dict) or set(split_plan) != {"train", "valid", "test"} or any(not isinstance(v, list) or not v for v in split_plan.values()):
        raise DatasetError("train·valid·test에 각각 프로젝트 그룹 목록이 필요합니다.")
    assignment = {}
    for split, groups in split_plan.items():
        for group in groups:
            if not isinstance(group, str) or group in assignment:
                raise DatasetError("프로젝트 그룹은 한 분할에만 한 번 배정해야 합니다.")
            assignment[group] = split
    bundle, contract_hash = prompt_contract()
    seen_ids, seen_inputs = set(), set()
    cases = {split: [] for split in split_plan}
    samples = {split: [] for split in split_plan}
    for index, record in enumerate(records, 1):
        try:
            validate_case(record)
        except DatasetError as error:
            raise DatasetError(f"사례 {index}: {error}") from None
        if record["caseId"] in seen_ids:
            raise DatasetError("사례 식별자가 중복됐습니다.")
        seen_ids.add(record["caseId"])
        split = assignment.get(record["projectGroup"])
        if split is None:
            raise DatasetError("배정표에 없는 프로젝트 그룹이 있습니다.")
        # ID만 다른 동일 입력도 서로 다른 분할에 들어가지 못하도록 한다.
        facts = {k: v for k, v in record["request"].items() if k != "projectId"}
        input_hash = digest(facts)
        if input_hash in seen_inputs:
            raise DatasetError("동일한 분석 입력이 중복됐습니다. 하나의 승인 사례로 정리하세요.")
        seen_inputs.add(input_hash)
        cases[split].append(record)
        for stage, answer in record["answers"].items():
            messages = messages_for(record, stage, bundle)
            messages.append({"role": "assistant", "content": json.dumps(answer, ensure_ascii=False, separators=(",", ":"))})
            samples[split].append({"messages": messages})
    if set(assignment) != {r["projectGroup"] for r in records} or any(not v for v in cases.values()):
        raise DatasetError("모든 배정 프로젝트와 세 분할에 실제 승인 사례가 있어야 합니다.")
    manifest = {"formatVersion": 1, "contractSha256": contract_hash,
                "caseSetSha256": digest(sorted(records, key=lambda r: r["caseId"])), "splitPlan": split_plan,
                "splitCaseSha256": {k: digest(sorted(v, key=lambda r: r["caseId"])) for k, v in cases.items()},
                "caseCounts": {k: len(v) for k, v in cases.items()},
                "stageSampleCounts": {k: len(v) for k, v in samples.items()}}
    return cases, samples, manifest


def export(records, split_plan, output):
    """검증 후 새 폴더를 원자적으로 생성한다. 기존 데이터셋은 덮어쓰지 않는다."""
    output = Path(output)
    if output.exists():
        raise DatasetError("출력 폴더가 이미 있습니다. 새 버전 경로를 사용하세요.")
    cases, samples, manifest = prepare(records, split_plan)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=".dataset-", dir=output.parent))
    try:
        for split in cases:
            for name, values in ((f"{split}.jsonl", samples[split]), (f"{split}_cases.jsonl", cases[split])):
                (temporary / name).write_text("".join(json.dumps(v, ensure_ascii=False) + "\n" for v in values))
        (temporary / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
        temporary.rename(output)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)
    return manifest


def main():
    """로컬 JSONL과 배정표를 받아 데이터셋을 생성한다. 원격 DB에는 접속하지 않는다."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="승인 사례 JSONL 경로")
    parser.add_argument("--splits", help="프로젝트 그룹 배정 JSON 경로")
    parser.add_argument("--output", help="새 데이터셋 폴더 경로")
    parser.add_argument("--validate-only", action="store_true", help="승인 사례를 검사하고 파일을 생성하지 않음")
    args = parser.parse_args()
    if not args.validate_only and (not args.splits or not args.output):
        parser.error("변환할 때는 --splits와 --output이 필요합니다.")
    try:
        records = read_jsonl(args.input)
        if args.validate_only:
            identifiers, inputs = set(), set()
            for record in records:
                validate_case(record)
                identity = digest({k: v for k, v in record["request"].items() if k != "projectId"})
                if record["caseId"] in identifiers or identity in inputs:
                    raise DatasetError("사례 ID 또는 동일한 분석 입력이 중복됐습니다.")
                identifiers.add(record["caseId"])
                inputs.add(identity)
            manifest = {"validCases": len(records), "projectGroups": len({r["projectGroup"] for r in records}),
                        "notice": "승인 표시는 입력 제공자의 확인입니다. 정답의 업무 타당성은 사람이 검토해야 합니다."}
        else:
            manifest = export(records, strict_json(Path(args.splits).read_text()), args.output)
    except DatasetError as error:
        raise SystemExit(f"데이터셋 생성 실패: {error}") from None
    except (json.JSONDecodeError, OSError):
        # 파일 경로와 원문이 포함될 수 있는 예외는 상세 출력하지 않는다.
        raise SystemExit("데이터셋 생성 실패: JSON 파일 형식과 출력 경로를 확인하세요.") from None
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
