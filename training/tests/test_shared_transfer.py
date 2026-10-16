"""공유 폴더 동기화 지연과 재전송을 합성 사례로 시험한다."""

import json
import sys
import tempfile
import unittest
from pathlib import Path
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from receiver_service import ReceiverStore
from shared_transfer import publish_case, signed_headers, verify_signature
from test_data_tools import case
from transfer_cases import TransferError


class SharedTransferTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.shared = self.root / "shared"
        self.shared.mkdir()
        self.job_id = uuid4()
        self.record = case()
        self.record["caseId"] = f"analysis-{self.job_id}"
        self.outbox = self.root / "outbox"
        self.status_dir = self.root / "status"
        self.receiver = ReceiverStore(self.shared, self.root / "receiver-state",
                                      self.root / "staging", self.root / "inbox")

    def publish(self):
        return publish_case(self.job_id, self.record, self.shared,
                            outbox=self.outbox, status_dir=self.status_dir)

    def test_wait_then_verify_after_file_arrives(self):
        self.assertEqual(self.receiver.intake_status()["status"], "WAITING_FOR_DATA")
        status = self.publish()
        file = self.shared / f'{status["sha256"]}.jsonl'
        file.rename(self.root / "held.jsonl")
        notice = self.receiver.notify({key: status[key] for key in ("sha256", "cases", "size")})
        self.assertEqual(notice["status"], "WAITING_FOR_FILE")
        self.assertEqual(self.receiver.tick(status["sha256"])["status"], "WAITING_FOR_FILE")
        (self.root / "held.jsonl").rename(file)
        self.receiver.tick_all()
        self.assertEqual(self.receiver.status(status["sha256"])["status"], "VERIFIED")
        self.assertEqual(self.receiver.intake_status(), {"status": "READY_FOR_DATASET", "files": 1, "cases": 1})
        self.assertEqual(json.loads((self.root / "inbox" / file.name).read_text()), self.record)

    def test_same_job_is_idempotent_and_different_answer_is_rejected(self):
        original = self.publish()
        self.assertEqual(self.publish()["sha256"], original["sha256"])
        self.record["approval"]["note"] = "다른 검토 내용"
        with self.assertRaises(TransferError):
            self.publish()

    def test_unapproved_case_is_not_published(self):
        self.record["humanApproved"] = False
        with self.assertRaises(ValueError):
            self.publish()
        self.assertEqual(list(self.shared.iterdir()), [])

    def test_missing_shared_folder_does_not_claim_publication(self):
        self.shared.rmdir()
        with self.assertRaises(OSError):
            self.publish()
        self.assertFalse((self.status_dir / f"{self.job_id}.json").exists())
        self.assertFalse(self.shared.exists())

    def test_signed_notice_rejects_tampering_and_expired_time(self):
        secret = "synthetic-transfer-key-for-testing-only"
        body = b'{"sha256":"example"}'
        headers = signed_headers(secret, "POST", "/api/training/transfers", body, timestamp=1000)
        self.assertTrue(verify_signature(secret, "POST", "/api/training/transfers", body, headers, now=1001))
        self.assertFalse(verify_signature(secret, "POST", "/api/training/transfers", body + b" ", headers, now=1001))
        self.assertFalse(verify_signature(secret, "POST", "/api/training/transfers", body, headers, now=1401))


if __name__ == "__main__":
    unittest.main()
