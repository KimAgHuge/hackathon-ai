"""승인 우회·데이터 누출·평가 왜곡을 막는 동작을 합성 사례로 검증한다."""

import copy
import json
import sys
import subprocess
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from dataset_contract import DatasetError, digest, strict_json, validate_case, validate_response, review_flags, messages_for, prompt_contract
from evaluate_model import compare_runs, evaluator_hash, load_test_set, score
from export_dataset import export, prepare


def case(group="p1", related=True):
    """테스트 메모리에서만 쓰는 합성 승인 사례를 만든다. 업무 데이터로 전달하지 않는다."""
    general = {"summary": "입력 근거를 확인하고 요구사항을 명확히 해야 합니다.",
               "plainLanguageSummary": "변경 범위를 정하려면 요청 내용을 더 확인해야 합니다.", "frameworks": [],
               "riskLevel": "UNKNOWN", "riskScore": 0,
               "riskAssessment": {key: {"score": 0, "reason": "합성 사례의 시험값입니다."}
                                  for key in ("changeScope", "dataImpact", "securityImpact", "deliveryComplexity", "uncertainty")},
               "impactAreas": [], "evidences": [], "recommendations": [],
               "databaseRelevance": "UNCERTAIN" if related else "UNRELATED",
               "databaseRelevanceReason": "팀 개념을 확인해야 합니다." if related else "화면 색상만 변경합니다."}
    answers = {"general": general}
    if related:
        answers["database"] = {"status": "NEEDS_CLARIFICATION", "explanation": "팀이 기존 조직인지 확인해야 합니다.",
                               "observedTables": ["public.projects"], "proposedChanges": [],
                               "conditionalPaths": [{"condition": "팀이 조직과 같은 경우", "databaseEffect": "기존 조직 연결을 확인합니다."},
                                                    {"condition": "팀이 별도 개념인 경우", "databaseEffect": "새로운 팀 연결을 검토합니다."}],
                               "openQuestions": ["팀이 조직과 같은 개념인가요?"]}
    return {"caseId": f"case-{group}", "projectGroup": group, "humanApproved": True, "sanitized": True,
            "approval": {"reviewerId": "reviewer-1", "approvedAt": "2026-10-14T10:00:00+09:00", "note": "합성 테스트 검토 표시"},
            "snapshot": {"sourceRevision": "a" * 40, "requirementVersionId": f"req-{group}", "databaseCapturedAt": "2026-10-14T09:00:00+09:00"},
            "request": {"projectId": "00000000-0000-0000-0000-000000000000",
                        "sourceSummary": {"changeRequirement": {"title": f"{group}: 팀 관리" if related else f"{group}: 버튼 색상"},
                                          "components": [{"path": "src/project.py", "line": 7}]},
                        "databaseSummary": {"type": "POSTGRESQL", "tables": [{"schema": "public", "name": "projects",
                                                                                 "columns": [{"name": "id"}]}]}, "relations": []},
            "answers": answers}


def predictions(record):
    """정답을 그대로 반환하는 시험 응답을 만든다. 실제 모델 측정으로 해석하지 않는다."""
    return [{"caseId": record["caseId"], "stage": stage, "rawText": json.dumps(answer, ensure_ascii=False),
             "seconds": 1.0, "finishReason": "stop"} for stage, answer in record["answers"].items()]


class DatasetTests(unittest.TestCase):
    """승인·근거·비식별 및 프로젝트 분리의 실패 경로를 확인한다."""

    def setUp(self):
        self.records = [case("p1"), case("p2"), case("p3", related=False)]
        self.splits = {"train": ["p1"], "valid": ["p2"], "test": ["p3"]}

    def test_training_and_operating_prompts_share_the_same_source_context(self):
        from app.services.source_context import general_overview
        from app.services.database_scope import database_source_context
        record = case()
        bundle, _ = prompt_contract()
        self.assertEqual(bundle["messageFormatVersion"], 3)
        general_message = messages_for(record, "general", bundle)[1]["content"]
        payload = json.loads(general_message.split("return only the required JSON object:\n", 1)[1])
        self.assertEqual(payload, general_overview(record["request"]))
        database_payload = json.loads(messages_for(record, "database", bundle)[1]["content"])
        self.assertEqual(database_payload["sourceContext"], database_source_context(record["request"], record["request"]["databaseSummary"]["tables"]))

    def test_unapproved_and_missing_review_rejected(self):
        for field in ("humanApproved", "sanitized"):
            record = case()
            record[field] = False
            with self.assertRaises(DatasetError):
                validate_case(record)
        record = case()
        record["approval"]["note"] = ""
        with self.assertRaises(DatasetError):
            validate_case(record)

    def test_existing_unapproved_baseline_rejected(self):
        path = Path(__file__).resolve().parents[2] / "docs/evaluation/qwen3.8_27b_team_management_baseline.json"
        with self.assertRaises(DatasetError):
            validate_case(json.loads(path.read_text()))

    def test_secret_field_email_and_token_rejected_without_echo(self):
        for payload in ({"password": "do-not-echo"}, {"note": "person@example.com"}, {"note": "hf_" + "a" * 30}):
            record = case()
            record["request"]["sourceSummary"]["extra"] = payload
            with self.assertRaises(DatasetError) as context:
                validate_case(record)
            self.assertNotIn("do-not-echo", str(context.exception))
            self.assertNotIn("person@example.com", str(context.exception))

    def test_group_overlap_rejected(self):
        self.splits["test"] = ["p1"]
        with self.assertRaises(DatasetError):
            prepare(self.records, self.splits)

    def test_duplicate_facts_with_different_ids_rejected(self):
        duplicate = copy.deepcopy(self.records[0])
        duplicate["caseId"], duplicate["projectGroup"] = "other", "p2"
        with self.assertRaises(DatasetError):
            prepare([self.records[0], duplicate, self.records[2]], self.splits)

    def test_unknown_group_and_duplicate_case_rejected(self):
        with self.assertRaises(DatasetError):
            prepare(self.records + [case("p4")], self.splits)
        self.records[1]["caseId"] = self.records[0]["caseId"]
        with self.assertRaises(DatasetError):
            prepare(self.records, self.splits)

    def test_general_and_database_are_separate_samples(self):
        cases, samples, manifest = prepare(self.records, self.splits)
        self.assertEqual(manifest["stageSampleCounts"], {"train": 2, "valid": 2, "test": 1})
        for sample in samples["train"]:
            self.assertEqual([m["role"] for m in sample["messages"]], ["system", "user", "assistant"])
            self.assertIn("Required JSON Schema", sample["messages"][0]["content"])
            self.assertNotIn("approval", sample["messages"][1]["content"])
        self.assertNotIn("databaseImpact", json.loads(samples["train"][0]["messages"][-1]["content"]))

    def test_missing_or_unnecessary_database_answer_rejected(self):
        record = case()
        del record["answers"]["database"]
        with self.assertRaises(DatasetError):
            validate_case(record)
        record = case(related=False)
        record["answers"]["database"] = case()["answers"]["database"]
        with self.assertRaises(DatasetError):
            validate_case(record)

    def test_hallucinated_table_column_and_file_rejected(self):
        record = case()
        record["answers"]["database"]["observedTables"] = ["public.missing"]
        with self.assertRaises(DatasetError):
            validate_case(record)
        record = case()
        record["answers"]["general"]["evidences"] = [{"file": "src/missing.py", "line": 7}]
        with self.assertRaises(DatasetError):
            validate_case(record)
        record = case()
        record["answers"]["database"].update(status="CHANGE_REQUIRED", proposedChanges=[{
            "targetTable": "public.projects", "targetColumn": "missing", "changeType": "DROP_COLUMN",
            "description": "컬럼을 변경합니다.", "rationale": "요구사항을 반영합니다."}])
        with self.assertRaises(DatasetError):
            validate_case(record)

    def test_nested_extra_field_rejected(self):
        answer = case()["answers"]["database"]
        answer["conditionalPaths"][0]["unknown"] = "추가 필드"
        with self.assertRaises(DatasetError):
            validate_response("database", answer)

    def test_file_only_evidence_does_not_invent_line_or_accept_unknown_file(self):
        for evidence in ({"file": "src/project.py"}, {"file": "src/project.py", "line": None}):
            record = case(related=False)
            record["answers"]["general"]["evidences"] = [evidence]
            before = copy.deepcopy(record)
            validate_case(record)
            self.assertEqual(record, before)
        for line in (0, -1, True, "7", 999):
            record = case(related=False)
            record["answers"]["general"]["evidences"] = [{"file": "src/project.py", "line": line}]
            with self.assertRaises(DatasetError):
                validate_case(record)
        record = case(related=False)
        record["answers"]["general"]["evidences"] = [{"file": "src/missing.py"}]
        record["request"]["sourceSummary"]["changeRequirement"]["userClaim"] = {"file": "src/missing.py", "line": 1}
        with self.assertRaises(DatasetError):
            validate_case(record)

    def test_excluded_database_scope_is_review_warning_not_keyword_rejection(self):
        record = case(related=False)
        record["request"]["sourceSummary"]["changeRequirement"]["title"] = "표시 건수만 변경하고 DB·테이블·컬럼·마이그레이션은 제외합니다."
        validate_case(record)
        self.assertIn("database_scope_requires_review", review_flags("general", record["answers"]["general"], record["request"]))
        record["answers"]["database"] = case()["answers"]["database"]
        with self.assertRaises(DatasetError):
            validate_case(record)

    def test_general_answer_requires_plain_language_summary(self):
        answer = case()["answers"]["general"]
        del answer["plainLanguageSummary"]
        with self.assertRaises(DatasetError):
            validate_response("general", answer)

    def test_duplicate_json_key_and_nonfinite_number_rejected(self):
        for raw in ('{"humanApproved":false,"humanApproved":true}', '{"value":NaN}', '{"value":Infinity}'):
            with self.assertRaises(DatasetError):
                strict_json(raw)

    def test_invalid_date_and_table_shape_rejected(self):
        record = case()
        record["approval"]["approvedAt"] = "2026-10-14"
        with self.assertRaises(DatasetError):
            validate_case(record)
        record = case()
        record["request"]["databaseSummary"]["tables"][0]["columns"] = ["id"]
        with self.assertRaises(DatasetError):
            validate_case(record)

    def test_numeric_metadata_and_placeholder_revision_rejected(self):
        for target, key, value in (("approval", "reviewerId", 123), ("snapshot", "requirementVersionId", 123),
                                   ("snapshot", "sourceRevision", "0" * 40)):
            record = case()
            record[target][key] = value
            with self.assertRaises(DatasetError):
                validate_case(record)

    def test_validation_only_cli_does_not_require_three_projects(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "cases.jsonl"
            source.write_text(json.dumps(case()) + "\n")
            script = Path(__file__).resolve().parents[1] / "export_dataset.py"
            result = subprocess.run([sys.executable, str(script), "--input", str(source), "--validate-only"],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(result.stdout)["validCases"], 1)
    def test_export_integrity_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "dataset"
            export(self.records, self.splits, output)
            cases, _, _ = load_test_set(output)
            self.assertEqual(cases, [self.records[2]])
            with self.assertRaises(DatasetError):
                export(self.records, self.splits, output)
            (output / "test_cases.jsonl").write_text(json.dumps(case("p3")) + "\n")
            with self.assertRaises(DatasetError):
                load_test_set(output)


class EvaluationTests(unittest.TestCase):
    """불완전 응답과 비교 조건 변경이 지표를 왜곡하지 않는지 확인한다."""

    def test_label_mismatch_is_not_schema_failure(self):
        record = case()
        values = predictions(record)
        answer = json.loads(values[1]["rawText"])
        answer.update(status="UNKNOWN", conditionalPaths=[], openQuestions=[])
        values[1]["rawText"] = json.dumps(answer)
        report = score([record], values)
        self.assertEqual(report["summary"]["all"]["schemaPassRate"], 1)
        self.assertEqual(report["summary"]["database"]["labelAgreementRate"], 0)

    def test_missing_duplicate_and_foreign_predictions_rejected(self):
        record = case()
        values = predictions(record)
        for invalid in (values[:1], values + values[:1], [{**values[0], "caseId": "foreign"}]):
            with self.assertRaises(DatasetError):
                score([record], invalid)

    def test_truncated_thinking_and_invalid_json_count_as_failures(self):
        record = case(related=False)
        values = predictions(record)
        values[0].update(rawText="<think>생각 중입니다.", finishReason="length")
        summary = score([record], values)["summary"]["all"]
        self.assertEqual(summary["schemaPassRate"], 0)
        self.assertEqual(summary["truncationRate"], 1)

    def test_completed_thinking_removed_before_json_validation(self):
        record = case(related=False)
        values = predictions(record)
        values[0]["rawText"] = "<think>시험용 생각</think>" + values[0]["rawText"]
        self.assertEqual(score([record], values)["summary"]["all"]["schemaPassRate"], 1)

    def test_sensitive_thinking_is_not_hidden_by_valid_final_answer(self):
        record = case(related=False)
        values = predictions(record)
        values[0]["rawText"] = "<think>person@example.com</think>" + values[0]["rawText"]
        self.assertEqual(score([record], values)["summary"]["all"]["schemaPassRate"], 0)

    def test_malformed_evidence_is_reported_without_crash(self):
        record = case(related=False)
        values = predictions(record)
        answer = record["answers"]["general"]
        answer["evidences"] = [{"file": ["invalid"], "line": 1}]
        values[0]["rawText"] = json.dumps(answer)
        self.assertEqual(score([record], values)["details"][0]["groundingIssues"], ["evidence_not_in_input"])

    def test_comparison_rejects_changed_conditions_and_recomputes_scores(self):
        record = case(related=False)
        manifest = {"status": "complete", "testCasesSha256": digest([record]), "contractSha256": "contract",
                    "evaluationPlanSha256": "plan", "model": {"revision": "pinned"}, "generation": {"thinking": True},
                    "evaluatorSha256": evaluator_hash(), "packages": {"mlx": "same"}, "adapterSha256": None}
        with tempfile.TemporaryDirectory() as directory:
            before, after = Path(directory) / "before", Path(directory) / "after"
            for path in (before, after):
                path.mkdir()
                (path / "cases.jsonl").write_text(json.dumps(record) + "\n")
                (path / "predictions.jsonl").write_text("".join(json.dumps(p) + "\n" for p in predictions(record)))
            (before / "manifest.json").write_text(json.dumps(manifest))
            adapted = {**manifest, "adapterSha256": "trained"}
            (after / "manifest.json").write_text(json.dumps(adapted))
            self.assertEqual(compare_runs(before, after)["delta"]["all"]["labelAgreementRate"], 0)
            adapted["generation"] = {"thinking": False}
            (after / "manifest.json").write_text(json.dumps(adapted))
            with self.assertRaises(DatasetError):
                compare_runs(before, after)


if __name__ == "__main__":
    unittest.main()
