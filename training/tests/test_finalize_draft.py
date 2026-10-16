"""학습 초안이 검토·비식별화 전에는 JSONL로 확정되지 않는지 확인한다."""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from finalize_draft import finalize
from test_data_tools import case


class FinalizeDraftTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "draft.json"
        self.destination = self.root / "approved.jsonl"
        self.record = case()

    def write_draft(self):
        self.source.write_text(json.dumps({"caseDraft": self.record, "reviewRequired": []}, ensure_ascii=False))

    def test_unreviewed_case_cannot_be_exported(self):
        self.record["humanApproved"] = False
        self.write_draft()
        with self.assertRaises(ValueError):
            finalize(self.source, self.destination)
        self.assertFalse(self.destination.exists())

    def test_missing_database_relevance_cannot_be_exported(self):
        self.record["answers"]["general"]["databaseRelevance"] = None
        self.write_draft()
        with self.assertRaises(ValueError):
            finalize(self.source, self.destination)
        self.assertFalse(self.destination.exists())

    def test_valid_case_is_written_once_without_overwrite(self):
        self.write_draft()
        result = finalize(self.source, self.destination)
        self.assertEqual(result["caseId"], self.record["caseId"])
        self.assertEqual(json.loads(self.destination.read_text()), self.record)
        original = self.destination.read_bytes()
        with self.assertRaisesRegex(ValueError, "덮어쓰지 않았습니다"):
            finalize(self.source, self.destination)
        self.assertEqual(self.destination.read_bytes(), original)


if __name__ == "__main__":
    unittest.main()
