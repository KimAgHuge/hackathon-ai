"""고정 시험 세트로 MLX 답변을 생성하고 학습 전후의 계약·판단·근거 지표를 비교한다."""

import argparse
import hashlib
import json
import math
import os
import time
from pathlib import Path

from dataset_contract import (
    ROOT, DatasetError, digest, ensure_sanitized, grounding_issues, messages_for, prompt_contract,
    read_jsonl, review_flags, strict_json, validate_case, validate_response,
)


def file_hash(path):
    """큰 어댑터도 메모리에 통째로 올리지 않고 파일 해시를 계산한다."""
    checksum = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            checksum.update(chunk)
    return checksum.hexdigest()


def evaluator_hash():
    """평가 실행 코드와 공통 검증 코드의 변경을 함께 추적한다."""
    return digest({"evaluator": file_hash(__file__), "contract": file_hash(ROOT / "dataset_contract.py")})


def load_test_set(dataset):
    """승인 시험 사례와 배정표·프롬프트 버전이 변하지 않았는지 확인한다."""
    dataset = Path(dataset)
    manifest = json.loads((dataset / "manifest.json").read_text())
    cases = read_jsonl(dataset / "test_cases.jsonl")
    bundle, contract_hash = prompt_contract()
    if manifest.get("contractSha256") != contract_hash:
        raise DatasetError("프롬프트·응답 계약이 변경됐습니다. 데이터셋을 새 버전으로 생성하세요.")
    if manifest.get("splitCaseSha256", {}).get("test") != digest(sorted(cases, key=lambda r: r["caseId"])):
        raise DatasetError("시험 사례가 데이터셋 manifest와 다릅니다.")
    seen = set()
    for case in cases:
        validate_case(case)
        if case["caseId"] in seen or case["projectGroup"] not in manifest["splitPlan"]["test"]:
            raise DatasetError("시험 사례 식별자가 중복되거나 프로젝트 배정이 다릅니다.")
        seen.add(case["caseId"])
    if len(cases) != manifest["caseCounts"]["test"]:
        raise DatasetError("시험 사례 수가 manifest와 다릅니다.")
    return cases, bundle, contract_hash


def final_text(raw):
    """완료된 thinking 영역만 분리한다. 코드 블록이나 불완전한 JSON은 자동 보정하지 않는다."""
    if not isinstance(raw, str):
        raise DatasetError("모델 응답은 문자열이어야 합니다.")
    if "</think>" in raw:
        raw = raw.split("</think>", 1)[1]
    elif "<think>" in raw:
        raise DatasetError("thinking이 끝나기 전에 생성이 중단됐습니다.")
    return raw.strip()


def score(cases, predictions):
    """누락·중복 응답을 거부하고 정답 일치율과 근거·형식 검사를 계산한다."""
    expected = {(case["caseId"], stage): (case, answer) for case in cases for stage, answer in case["answers"].items()}
    seen, details = set(), []
    for prediction in predictions:
        key = (prediction.get("caseId"), prediction.get("stage"))
        if key not in expected or key in seen:
            raise DatasetError("평가 응답에 중복 또는 알 수 없는 사례·단계가 있습니다.")
        seen.add(key)
        case, answer = expected[key]
        seconds = prediction.get("seconds")
        if not isinstance(seconds, (int, float)) or not math.isfinite(seconds) or seconds < 0:
            raise DatasetError("응답 시간 측정값이 유효하지 않습니다.")
        item = {"caseId": key[0], "stage": key[1], "schemaValid": False, "labelMatch": False,
                "groundingIssues": [], "reviewFlags": [], "seconds": seconds,
                "truncated": prediction.get("finishReason") != "stop"}
        try:
            ensure_sanitized(prediction.get("rawText"))
            parsed = strict_json(final_text(prediction.get("rawText")))
            parsed = validate_response(key[1], parsed)
        except (DatasetError, json.JSONDecodeError):
            item["reviewFlags"] = ["invalid_or_sensitive_response"]
        else:
            item["schemaValid"] = True
            label = "databaseRelevance" if key[1] == "general" else "status"
            item["expectedLabel"], item["actualLabel"] = answer[label], parsed[label]
            item["labelMatch"] = answer[label] == parsed[label]
            item["groundingIssues"] = grounding_issues(key[1], parsed, case["request"])
            item["reviewFlags"] = review_flags(key[1], parsed, case["request"])
        details.append(item)
    if seen != set(expected):
        raise DatasetError("시험 사례·단계의 응답이 빠져 있습니다.")
    summary = {}
    for stage in ("all", "general", "database"):
        rows = [d for d in details if stage == "all" or d["stage"] == stage]
        count = len(rows)
        summary[stage] = {"count": count, "schemaPassRate": sum(d["schemaValid"] for d in rows) / count if count else None,
                          "labelAgreementRate": sum(d["labelMatch"] for d in rows) / count if count else None,
                          "groundedResponseRate": sum(d["schemaValid"] and not d["groundingIssues"] for d in rows) / count if count else None,
                          "humanReviewFlagRate": sum(bool(d["reviewFlags"]) for d in rows) / count if count else None,
                          "truncationRate": sum(d["truncated"] for d in rows) / count if count else None,
                          "meanSeconds": sum(d["seconds"] for d in rows) / count if count else None}
    return {"summary": summary, "details": sorted(details, key=lambda d: (d["caseId"], d["stage"])),
            "notice": "라벨 일치와 정적 검사는 의미의 정확도나 위험한 제안 비율을 확정하지 않습니다. 사람이 답변을 검토해야 합니다."}


def compare_runs(before, after):
    """모델·시험 입력·생성 조건이 동일한 완료 평가 두 개만 비교한다."""
    before, after = Path(before), Path(after)
    left = json.loads((before / "manifest.json").read_text())
    right = json.loads((after / "manifest.json").read_text())
    if left.get("status") != "complete" or right.get("status") != "complete":
        raise DatasetError("완료되지 않은 평가를 비교할 수 없습니다.")
    for key in ("testCasesSha256", "contractSha256", "evaluationPlanSha256", "model", "generation", "evaluatorSha256", "packages"):
        if key not in left or left[key] != right.get(key):
            raise DatasetError("시험 입력·모델·프롬프트·생성 조건·평가 코드가 서로 다릅니다.")
    if left["evaluatorSha256"] != evaluator_hash():
        raise DatasetError("현재 평가 코드가 실행 당시 버전과 다릅니다.")
    if left.get("adapterSha256") is not None or not right.get("adapterSha256"):
        raise DatasetError("학습 전은 원본 모델, 학습 후는 어댑터가 있는 평가여야 합니다.")
    reports = []
    for directory, manifest in ((before, left), (after, right)):
        cases = read_jsonl(directory / "cases.jsonl")
        if digest(sorted(cases, key=lambda c: c["caseId"])) != manifest["testCasesSha256"]:
            raise DatasetError("평가에 보관된 시험 사례가 변경됐습니다.")
        for case in cases:
            validate_case(case)
        reports.append(score(cases, read_jsonl(directory / "predictions.jsonl")))
    delta = {}
    for stage in reports[0]["summary"]:
        delta[stage] = {k: reports[1]["summary"][stage][k] - value
                        for k, value in reports[0]["summary"][stage].items()
                        if k != "count" and value is not None}
    return {"before": reports[0], "after": reports[1], "delta": delta,
            "adapterSha256": right["adapterSha256"]}


def run(args):
    """MLX를 필요한 시점에만 불러와 GPU 평가를 실행하고 원본 답변을 로컬에 저장한다."""
    cases, bundle, contract_hash = load_test_set(args.dataset)
    output, model_path = Path(args.output), Path(args.model)
    if output.exists():
        raise DatasetError("출력 폴더가 이미 있습니다. 새 평가 경로를 사용하세요.")
    model_identity = json.loads((model_path / "download_manifest.json").read_text())
    generation = {"thinking": args.thinking == "on", "reasoningEffort": args.reasoning_effort,
                  "maxTokens": args.max_tokens, "maxPromptTokens": args.max_prompt_tokens, "temperature": 0, "seed": 0,
                  "strategy": "fixed_approved_stages_no_retry"}
    plan = [{"caseId": c["caseId"], "stage": stage, "messages": messages_for(c, stage, bundle)}
            for c in sorted(cases, key=lambda c: c["caseId"]) for stage in sorted(c["answers"])]
    adapter_hash = None
    if args.adapter:
        adapter = Path(args.adapter)
        config = json.loads((adapter / "adapter_config.json").read_text())
        if "infrastructure" in config.get("purpose", ""):
            raise DatasetError("호환성 시험용 어댑터를 업무 품질 평가에 사용하지 마세요.")
        adapter_hash = digest({"config": config, "weightsSha256": file_hash(adapter / "adapters.safetensors")})
    os.environ.setdefault("HF_HOME", str(ROOT / ".cache/huggingface"))
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
    import mlx.core as mx
    from mlx_lm import load, stream_generate
    from mlx_lm.sample_utils import make_sampler
    import importlib.metadata

    mx.random.seed(0)
    model, tokenizer = load(str(model_path), adapter_path=args.adapter)
    prompts = []
    for item in plan:
        prompt = tokenizer.apply_chat_template(item["messages"], tokenize=False, add_generation_prompt=True,
                                              enable_thinking=generation["thinking"], reasoning_effort=args.reasoning_effort)
        if len(tokenizer.encode(prompt)) > args.max_prompt_tokens:
            raise DatasetError("입력이 설정한 토큰 상한을 넘었습니다. 자동으로 근거를 자르지 않습니다.")
        prompts.append(prompt)
    output.mkdir(parents=True)
    manifest = {"status": "running", "testCasesSha256": digest(sorted(cases, key=lambda c: c["caseId"])),
                "contractSha256": contract_hash, "evaluationPlanSha256": digest({"plan": plan, "renderedPrompts": prompts}),
                "model": {k: model_identity[k] for k in ("modelId", "revision")}, "generation": generation,
                "adapterSha256": adapter_hash, "evaluatorSha256": evaluator_hash(),
                "packages": {k: importlib.metadata.version(k) for k in ("mlx", "mlx-lm", "transformers", "tokenizers", "pydantic")}}
    manifest_path = output / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    (output / "cases.jsonl").write_text("".join(json.dumps(c, ensure_ascii=False) + "\n" for c in cases))
    predictions = []
    with (output / "predictions.jsonl").open("w") as handle:
        for index, (item, prompt) in enumerate(zip(plan, prompts), 1):
            print(f"평가 {index}/{len(plan)}: {item['caseId']} / {item['stage']}", flush=True)
            mx.reset_peak_memory()
            started = time.perf_counter()
            fragments = []
            for response in stream_generate(model, tokenizer, prompt, max_tokens=args.max_tokens, sampler=make_sampler(temp=0)):
                fragments.append(response.text)
            prediction = {"caseId": item["caseId"], "stage": item["stage"], "rawText": "".join(fragments),
                          "seconds": time.perf_counter() - started, "finishReason": response.finish_reason,
                          "generatedTokens": response.generation_tokens, "promptTokens": response.prompt_tokens,
                          "peakGpuGiB": mx.get_peak_memory() / 2**30}
            predictions.append(prediction)
            handle.write(json.dumps(prediction, ensure_ascii=False) + "\n")
            handle.flush()
            mx.clear_cache()
    report = score(cases, predictions)
    (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    manifest["status"] = "complete"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    return report["summary"]


def main():
    """GPU 평가 실행 또는 CPU에서 완료 평가의 비교를 선택한다."""
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    execute = commands.add_parser("run", help="고정 시험 세트의 답변 생성 및 평가")
    execute.add_argument("--dataset", required=True, help="변환 도구가 생성한 고정 데이터셋 폴더")
    execute.add_argument("--model", default=str(ROOT / "models/Qwen3.8-27B-4bit"), help="revision manifest가 있는 로컬 모델 폴더")
    execute.add_argument("--adapter", help="학습 후 평가할 업무 어댑터 폴더. 생략하면 원본 모델 평가")
    execute.add_argument("--output", required=True, help="새 평가 결과 폴더")
    execute.add_argument("--thinking", choices=["on", "off"], default="on", help="모델 thinking 활성화 여부")
    execute.add_argument("--reasoning-effort", choices=["xhigh", "medium", "low"], default="xhigh", help="thinking의 추론 수준")
    execute.add_argument("--max-tokens", type=int, default=2048, help="thinking을 포함한 생성 토큰 상한")
    execute.add_argument("--max-prompt-tokens", type=int, default=8192, help="입력 상한. 초과하면 자르지 않고 실패")
    compare = commands.add_parser("compare", help="원본 모델과 학습 어댑터의 동일 조건 평가 비교")
    compare.add_argument("--before", required=True, help="원본 모델의 완료 평가 폴더")
    compare.add_argument("--after", required=True, help="학습 어댑터의 완료 평가 폴더")
    compare.add_argument("--output", required=True, help="새 비교 결과 JSON 파일")
    args = parser.parse_args()
    try:
        if args.command == "run":
            if args.max_tokens < 1 or args.max_prompt_tokens < 1:
                raise DatasetError("토큰 상한은 양수여야 합니다.")
            result = run(args)
        else:
            if Path(args.output).exists():
                raise DatasetError("비교 결과 파일이 이미 있습니다.")
            result = compare_runs(args.before, args.after)
            Path(args.output).parent.mkdir(parents=True, exist_ok=True)
            Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    except DatasetError as error:
        raise SystemExit(f"평가 실패: {error}") from None
    except (OSError, KeyError, json.JSONDecodeError):
        raise SystemExit("평가 실패: 입력 파일·모델 파일·manifest 형식을 확인하세요.") from None
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
