"""검증 수신함의 사본을 일반 단계 시험 학습 자료로만 준비한다."""

import argparse
import json
import os
from pathlib import Path

from dataset_contract import DatasetError, digest, messages_for, prompt_contract, read_jsonl, validate_case
from shared_transfer import write_json_atomic
from trial_dataset import prepare_trial, tokenize_messages


def automatic_splits(records, tokenizer, max_tokens=18000):
    """짧은 서로 다른 두 요구사항을 학습·검증에 두고 나머지는 시험 분할로 보관한다."""
    groups = {}
    bundle, _ = prompt_contract()
    for record in records:
        validate_case(record)
        requirement = record["request"]["sourceSummary"]["changeRequirement"].get("requirementId")
        if not isinstance(requirement, str) or not requirement.strip():
            raise DatasetError("원 요구사항 식별자가 필요합니다.", code="DATASET_INVALID")
        identity = digest([record["projectGroup"], requirement])
        messages = messages_for(record, "general", bundle)
        messages.append({"role": "assistant", "content": json.dumps(record["answers"]["general"], ensure_ascii=False, separators=(",", ":"))})
        tokens, _ = tokenize_messages(tokenizer, messages)
        group = groups.setdefault(identity, {"caseIds": [], "minimumTokens": len(tokens)})
        group["caseIds"].append(record["caseId"])
        group["minimumTokens"] = min(group["minimumTokens"], len(tokens))
    ordered = sorted(groups, key=lambda identity: (groups[identity]["minimumTokens"], identity))
    if len(ordered) < 3 or groups[ordered[1]]["minimumTokens"] > max_tokens:
        raise DatasetError("서로 다른 원 요구사항의 짧은 학습·검증 자료와 별도 시험 그룹이 필요합니다.", code="DATASET_TOO_SMALL")
    representatives = {identity: min(groups[identity]["caseIds"]) for identity in ordered}
    # 같은 요구사항의 모든 버전은 prepare_trial에서 대표 사례와 같은 분할로 묶인다.
    return {"train": [representatives[ordered[0]]], "valid": [representatives[ordered[1]]],
            "test": [representatives[identity] for identity in ordered[2:]]}


def prepare(input_dir, output, model):
    """기존 시험 준비 규칙을 그대로 쓰며 새 폴더만 만들고 원본은 보존한다."""
    from transformers import AutoTokenizer

    records = []
    for source in sorted(input_dir.glob("*.jsonl")):
        if source.is_symlink() or not source.is_file():
            raise DatasetError("자료 사본을 확인할 수 없습니다.", code="DATASET_INVALID")
        records.extend(read_jsonl(source))
    tokenizer = AutoTokenizer.from_pretrained(str(model), local_files_only=True, trust_remote_code=False)
    plan = automatic_splits(records, tokenizer)
    samples, manifest = prepare_trial(records, plan, tokenizer, 18000)
    if not samples["train"] or not samples["valid"]:
        raise DatasetError("짧은 학습·검증 자료가 필요합니다.", code="DATASET_TOO_SMALL")
    manifest["model"] = json.loads((model / "download_manifest.json").read_text())
    output.mkdir(mode=0o700, exist_ok=False)
    for split, values in samples.items():
        with (output / f"{split}.jsonl").open("x") as target:
            os.fchmod(target.fileno(), 0o600)
            for value in values:
                target.write(json.dumps(value, ensure_ascii=False) + "\n")
    write_json_atomic(output / "manifest.json", manifest)
    write_json_atomic(output.parent / "splits.json", plan)


def main():
    """원문 오류를 출력하지 않는 고정된 준비 명령의 진입점이다."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    args = parser.parse_args()
    os.environ["HF_HUB_OFFLINE"] = "1"
    try:
        prepare(args.input_dir, args.output, args.model)
    except Exception as error:
        code = error.code if isinstance(error, DatasetError) and error.code in {"DATASET_TOO_SMALL", "DATASET_INVALID"} else "PREPARATION_FAILED"
        write_json_atomic(args.output.parent / "preparation-result.json", {"code": code})
        print("학습 자료 준비 실패 code=" + code, flush=True)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
