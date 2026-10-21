"""어댑터로 생성한 승인 자료의 출처 검증과 이전 자료 호환성을 확인한다."""

import copy
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from dataset_contract import (DatasetError, VALIDATION_FEATURES, messages_for, prompt_contract,
                              required_validation_features, validate_case)
from export_dataset import prepare
from test_data_tools import case

SOURCE = "qwen3.8:27b"
ADAPTER = {"model": "doenaai-mlx:web-1c63d5be-5f69-4cca-8aee-8e34a983471a", "digest": "a" * 64}


def adapter_case(group="p1"):
    """생성 당시 기반 모델과 어댑터를 함께 기록한 합성 승인 사례다."""
    return {**case(group, related=False), "sourceModel": SOURCE, "sourceAdapter": dict(ADAPTER)}


class AdapterProvenanceTests(unittest.TestCase):
    def test_valid_adapter_origin_is_preserved_and_requires_both_receiver_features(self):
        record = adapter_case()
        original = copy.deepcopy(record)
        self.assertEqual(validate_case(record), original)
        self.assertEqual(required_validation_features(record), {"SOURCE_MODEL_V1", "SOURCE_ADAPTER_V1"})
        self.assertIn("SOURCE_ADAPTER_V1", VALIDATION_FEATURES)
        self.assertEqual(record, original)

    def test_legacy_and_plain_model_cases_keep_their_previous_contract(self):
        for source in (None, SOURCE, "devstral:latest"):
            with self.subTest(source=source):
                record = case(related=False)
                if source is not None:
                    record["sourceModel"] = source
                self.assertEqual(validate_case(record), record)
                self.assertEqual(required_validation_features(record), {"SOURCE_MODEL_V1"} if source else set())

    def test_adapter_origin_without_a_base_source_model_is_rejected(self):
        record = adapter_case()
        record.pop("sourceModel")
        with self.assertRaises(DatasetError):
            validate_case(record)

    def test_adapter_metadata_rejects_wrong_types_extra_fields_and_path_inputs(self):
        private = "private-original-should-not-appear"
        malformed = [None, [], "invalid", {}, {"model": ADAPTER["model"]}, {"digest": ADAPTER["digest"]},
                     {**ADAPTER, "path": "/private/" + private},
                     {**ADAPTER, "model": "https://example.invalid/" + private},
                     {**ADAPTER, "model": "doenaai-mlx:web-../../" + private},
                     {**ADAPTER, "model": "qwen3.8:27b"}, {**ADAPTER, "model": 42},
                     {**ADAPTER, "digest": 42}, {**ADAPTER, "digest": "a" * 63},
                     {**ADAPTER, "digest": "a" * 65}, {**ADAPTER, "digest": "A" * 64},
                     {**ADAPTER, "digest": "sha256:" + "a" * 64}]
        for metadata in malformed:
            with self.subTest(metadata=metadata):
                record = adapter_case()
                record["sourceAdapter"] = metadata
                with self.assertRaises(DatasetError) as caught:
                    validate_case(record)
                self.assertNotIn(private, str(caught.exception))

    def test_provenance_is_kept_in_approved_cases_and_excluded_from_model_messages(self):
        records = [adapter_case("p1"), adapter_case("p2"), adapter_case("p3")]
        original = copy.deepcopy(records)
        approved, samples, _ = prepare(records, {"train": ["p1"], "valid": ["p2"], "test": ["p3"]})
        retained = [record for split in approved.values() for record in split]
        self.assertEqual(len(retained), 3)
        self.assertTrue(all(record["sourceModel"] == SOURCE and record["sourceAdapter"] == ADAPTER for record in retained))
        self.assertEqual(records, original)
        for split in ("train", "valid", "test"):
            text = json.dumps(samples[split], ensure_ascii=False)
            self.assertNotIn(ADAPTER["model"], text)
            self.assertNotIn(ADAPTER["digest"], text)
        bundle, _ = prompt_contract()
        messages = json.dumps(messages_for(records[0], "general", bundle), ensure_ascii=False)
        self.assertNotIn("sourceAdapter", messages)
