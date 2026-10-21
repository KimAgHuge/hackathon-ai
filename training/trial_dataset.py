"""승인 자료를 원 요구사항별로 묶고 자르지 않은 짧은 시험 학습 입력을 만든다."""

import argparse
import json
import os
from pathlib import Path

from dataset_contract import DatasetError, digest, grounding_issues, messages_for, prompt_contract, read_jsonl, validate_case
from transfer_cases import content_hash


def requirement_groups(records, split_cases):
    """같은 프로젝트·원 요구사항의 모든 버전과 후속 질문을 한 분할에 배정한다."""
    if set(split_cases) != {"train", "valid", "test"}:
        raise DatasetError("학습·검증·시험의 대표 사례를 각각 지정하세요.")
    identities, inputs, by_id = {}, set(), {}
    for record in records:
        validate_case(record)
        case_id = record["caseId"]
        requirement = record["request"]["sourceSummary"]["changeRequirement"]
        original_id = requirement.get("requirementId")
        if not isinstance(original_id, str) or not original_id.strip():
            raise DatasetError("원 요구사항 ID가 없어 안전하게 분리할 수 없습니다.")
        fingerprint = digest({k: v for k, v in record["request"].items() if k != "projectId"})
        if case_id in by_id or fingerprint in inputs:
            raise DatasetError("사례 ID 또는 동일한 분석 입력이 중복됐습니다.")
        inputs.add(fingerprint)
        by_id[case_id] = record
        identities[case_id] = digest([record["projectGroup"], original_id])
    assigned = {}
    for split, representatives in split_cases.items():
        if not isinstance(representatives, list) or not representatives:
            raise DatasetError("각 분할에 실제 대표 사례가 필요합니다.")
        for case_id in representatives:
            if case_id not in identities:
                raise DatasetError("분할 배정에 수신하지 않은 사례가 있습니다.")
            identity = identities[case_id]
            if identity in assigned and assigned[identity] != split:
                raise DatasetError("같은 원 요구사항의 버전·후속 질문을 나눌 수 없습니다.")
            assigned[identity] = split
    if set(assigned) != set(identities.values()):
        raise DatasetError("모든 원 요구사항을 명시적으로 배정해야 합니다.")
    return {case_id: assigned[identity] for case_id, identity in identities.items()}, identities


def tokenize_messages(tokenizer, messages):
    """승인된 JSON 정답 전체를 보존하고 프롬프트와 정답의 토큰 경계를 확인한다."""
    tokens = tokenizer.apply_chat_template(messages, return_dict=False, enable_thinking=False)
    prefix = tokenizer.apply_chat_template(messages[:-1], return_dict=False,
                                           add_generation_prompt=True, enable_thinking=False)
    if tokens[:len(prefix)] != prefix or len(prefix) >= len(tokens) - 1:
        raise DatasetError("프롬프트와 승인 정답의 토큰 경계가 일치하지 않습니다.")
    return tokens, len(prefix)


def prepare_trial(records, split_cases, tokenizer, max_tokens):
    """일반 단계만 시험하고 긴 자료와 단계별 입력을 복원하지 못한 DB 정답은 보관한다."""
    assignment, families = requirement_groups(records, split_cases)
    bundle, contract_hash = prompt_contract()
    samples = {split: [] for split in ("train", "valid", "test")}
    details = []
    for record in records:
        split = assignment[record["caseId"]]
        for stage, answer in record["answers"].items():
            item = {"caseId": record["caseId"], "requirementGroup": families[record["caseId"]],
                    "split": split, "stage": stage}
            if stage != "general":
                details.append({**item, "included": False, "reason": "분할 DB 호출의 단계별 승인 정답이 없어 원본만 보관합니다."})
                continue
            messages = messages_for(record, stage, bundle)
            marker = "Analyze these project facts and return only the required JSON object:\n"
            payload = json.loads(messages[1]["content"].split(marker, 1)[1])
            if grounding_issues("general", answer, payload):
                raise DatasetError("실제로 전달할 일반 입력에 승인 정답의 인용 근거가 없습니다.")
            messages.append({"role": "assistant", "content": json.dumps(answer, ensure_ascii=False, separators=(",", ":"))})
            tokens, offset = tokenize_messages(tokenizer, messages)
            included = len(tokens) <= max_tokens
            details.append({**item, "included": included, "tokens": len(tokens), "promptTokens": offset,
                            "answerTokens": len(tokens) - offset,
                            "reason": "전체 정답을 보존했습니다." if included else "길이 상한을 넘어 자르지 않고 보관합니다."})
            if included:
                samples[split].append({**item, "messages": messages, "tokens": tokens, "offset": offset})
    if not samples["train"]:
        raise DatasetError("길이 상한 안에 들어오는 승인 학습 자료가 없습니다.")
    manifest = {"purpose": "approved_case_smoke_test_do_not_deploy", "contractSha256": contract_hash,
                "messageFormatVersion": bundle["messageFormatVersion"], "caseAssignment": assignment,
                "requirementGroups": families, "maxTokens": max_tokens, "thinking": False,
                "truncatedSamples": 0, "caseSetSha256": digest(sorted(records, key=lambda r: r["caseId"])),
                "stageSampleCounts": {key: len(value) for key, value in samples.items()}, "details": details,
                "notice": "현재 로컬 코드의 일반 입력을 사용한 실행 시험입니다. 운영 품질 평가는 아닙니다."}
    return samples, manifest


def main():
    """수신 원본을 변경하지 않고 새 시험 폴더만 만든다."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", type=Path, default=Path(__file__).parent / "datasets/incoming")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--splits", type=Path, required=True)
    parser.add_argument("--model", type=Path, default=Path(__file__).parent / "models/Qwen3.8-27B-4bit")
    parser.add_argument("--max-tokens", type=int, default=18000)
    args = parser.parse_args()
    os.environ.setdefault("HF_HUB_OFFLINE", "1")
    os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
    from transformers import AutoTokenizer
    records, sources = [], []
    for source in sorted(args.input_dir.glob("*.jsonl")):
        checksum, size = content_hash(source)
        if source.is_symlink() or source.stem != checksum:
            raise DatasetError("수신 파일의 이름과 해시가 일치하지 않습니다.")
        records.extend(read_jsonl(source))
        sources.append({"file": source.name, "sha256": checksum, "bytes": size})
    tokenizer = AutoTokenizer.from_pretrained(str(args.model), local_files_only=True, trust_remote_code=False)
    samples, manifest = prepare_trial(records, json.loads(args.splits.read_text()), tokenizer, args.max_tokens)
    manifest["sources"] = sources
    manifest["model"] = json.loads((args.model / "download_manifest.json").read_text())
    args.output.mkdir(parents=True, exist_ok=False, mode=0o700)
    for split, values in samples.items():
        path = args.output / f"{split}.jsonl"
        with path.open("x") as target:
            path.chmod(0o600)
            for value in values:
                target.write(json.dumps(value, ensure_ascii=False) + "\n")
    (args.output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"sampleCounts": manifest["stageSampleCounts"], "details": manifest["details"]}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
