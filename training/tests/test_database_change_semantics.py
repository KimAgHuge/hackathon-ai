"""새 DB 변경 유형을 학습 수신 계약에서도 확인하고 구형 승인 사례는 임의 변환하지 않는다."""

import copy
import sys
from pathlib import Path

import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from dataset_contract import DatasetError, required_validation_features, validate_case, validate_response
from test_data_tools import case


def record_with_changes():
    record = case()
    record["answers"]["database"] = {
        "status": "CHANGE_REQUIRED", "explanation": "명시한 새 컬럼은 필요하며 인덱스는 성능 확인 후 선택합니다.",
        "observedTables": ["public.projects"], "conditionalPaths": [], "openQuestions": [],
        "proposedChanges": [
            {"targetTable": "public.projects", "targetColumn": "column_example", "changeType": "ADD_COLUMN",
             "necessity": "REQUIRED", "description": "새 정보를 저장할 컬럼을 추가합니다.", "rationale": "요구사항에 저장 방식이 명시돼 있습니다."},
            {"targetTable": "public.projects", "targetColumn": "column_example", "changeType": "ADD_INDEX",
             "necessity": "RECOMMENDED", "description": "검색 비용 확인 후 인덱스를 검토합니다.", "rationale": "실제 성능 근거가 없는 선택적 개선입니다."},
        ],
    }
    return record


class DatabaseChangeSemanticsTests(unittest.TestCase):
    def test_new_required_column_and_optional_index_are_validated_without_mutating_the_case(self):
        record = record_with_changes()
        original = copy.deepcopy(record)
        validate_case(record)
        self.assertEqual(record, original)
        self.assertIn("DATABASE_CHANGE_SEMANTICS_V2", required_validation_features(record))

    def test_legacy_case_without_classification_is_not_rewritten_or_assumed_required(self):
        record = record_with_changes()
        record["answers"]["database"]["proposedChanges"] = record["answers"]["database"]["proposedChanges"][:1]
        record["answers"]["database"]["proposedChanges"][0].pop("necessity")
        answer = record["answers"]["database"]
        self.assertEqual(validate_response("database", answer), answer)
        validate_case(record)
        self.assertNotIn("DATABASE_CHANGE_SEMANTICS_V2", required_validation_features(record))

    def test_invalid_semantics_are_rejected_before_training_publication(self):
        for problem in ("duplicate", "recommendation_only", "unknown_index_column"):
            with self.subTest(problem=problem):
                record = record_with_changes()
                changes = record["answers"]["database"]["proposedChanges"]
                if problem == "duplicate":
                    changes.append(copy.deepcopy(changes[0]))
                elif problem == "recommendation_only":
                    record["answers"]["database"]["proposedChanges"] = changes[1:]
                else:
                    changes[1]["targetColumn"] = "column_example_missing"
                with self.assertRaises(DatasetError):
                    validate_case(record)
