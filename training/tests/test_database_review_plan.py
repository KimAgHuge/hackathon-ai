import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from dataset_contract import DatasetError, messages_for, prompt_contract, required_validation_features, validate_case
from test_data_tools import case


class DatabaseReviewPlanTests(unittest.TestCase):
    def record(self):
        record = case()
        record["request"]["relations"] = [{"schema": "public", "table": "projects", "file": "src/project.py", "line": 7,
                                              "tentative": False, "ambiguous": False}]
        record["request"]["databaseSummary"]["tables"].append({"schema": "example", "name": "table_example", "columns": [{"name": "id"}]})
        record["answers"]["general"]["databaseReviewPlan"] = {
            "targetTables": ["public.projects"], "evidenceFiles": ["src/project.py"], "requiresFullReview": False,
            "reason": "실제 소스 참조와 대상 테이블을 대조했습니다."}
        return record

    def test_plan_needs_receiver_support_and_matches_operating_scope(self):
        record = self.record()
        validate_case(record)
        self.assertIn("DATABASE_REVIEW_PLAN_V1", required_validation_features(record))
        bundle, _ = prompt_contract()
        payload = json.loads(messages_for(record, "database", bundle)[1]["content"])
        self.assertEqual([table["name"] for table in payload["tables"]], ["projects"])
        self.assertEqual(payload["reviewScope"]["mode"], "FOCUSED")
        self.assertEqual(payload["metadataChecks"][0]["columnCount"], 1)

    def test_unknown_or_test_only_plan_is_not_accepted_as_a_training_answer(self):
        for change in ("file", "target", "test"):
            record = self.record()
            plan = record["answers"]["general"]["databaseReviewPlan"]
            if change == "file":
                plan["evidenceFiles"] = ["unknown/file.py"]
            elif change == "target":
                plan["targetTables"] = ["example.table_example"]
            else:
                record["request"]["relations"][0]["file"] = "tests/test_store.py"
                record["request"]["sourceSummary"]["components"].append({"path": "tests/test_store.py", "line": 7, "type": "TEST"})
                plan["evidenceFiles"] = ["tests/test_store.py"]
            with self.assertRaises(DatasetError):
                validate_case(record)
