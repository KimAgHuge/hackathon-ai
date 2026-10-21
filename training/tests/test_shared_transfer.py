"""공유 폴더 동기화 지연과 재전송을 합성 사례로 시험한다."""

import json
import hashlib
import sys
import tempfile
import unittest
from pathlib import Path
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from receiver_service import ReceiverStore, make_handler, CAPABILITIES_PATH
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

    def test_receiver_capabilities_require_authorization_and_report_validation_features(self):
        handler_type = make_handler(self.receiver, "synthetic-transfer-key-for-testing-only")
        handler = handler_type.__new__(handler_type)
        handler.path = CAPABILITIES_PATH
        responses = []
        handler._send = lambda status, value: responses.append((status, value))
        handler._authorized = lambda body: False
        handler.do_GET()
        self.assertEqual(responses[-1][0], 401)
        handler._authorized = lambda body: True
        handler.do_GET()
        self.assertEqual(responses[-1][0], 200)
        self.assertEqual(responses[-1][1]["features"], ["FILE_LEVEL_EVIDENCE", "DATABASE_SCOPE_REVIEW", "DATABASE_CHANGE_SEMANTICS_V2", "DATABASE_REVIEW_PLAN_V1", "SOURCE_MODEL_V1"])
        self.assertFalse(responses[-1][1]["trainingExecution"])
        self.assertIn("trainingExecutionReason", responses[-1][1])

    def test_file_only_case_with_db_exclusion_reaches_verified_inbox(self):
        self.record = case(related=False)
        self.record["caseId"] = f"analysis-{self.job_id}"
        self.record["answers"]["general"]["evidences"] = [{"file": "src/project.py"}]
        self.record["request"]["sourceSummary"]["changeRequirement"]["title"] = "표시 설정 변경이며 DB 변경은 제외합니다."
        status = self.publish()
        self.receiver.notify({key: status[key] for key in ("sha256", "cases", "size")})
        self.receiver.tick_all()
        self.assertEqual(self.receiver.status(status["sha256"])["status"], "VERIFIED")

    def test_lifecycle_logs_do_not_repeat_waiting_or_expose_case_content(self):
        status = self.publish()
        package = self.shared / f'{status["sha256"]}.jsonl'
        held = self.root / "held.jsonl"
        package.rename(held)
        with self.assertLogs("doenaai.training.receiver", level="INFO") as logs:
            metadata = {key: status[key] for key in ("sha256", "cases", "size")}
            self.receiver.notify(metadata)
            self.receiver.notify(metadata)
            self.receiver.tick_all()
            self.receiver.tick_all()
            held.rename(package)
            self.receiver.tick_all()
            self.receiver.tick_all()
        output = "\n".join(logs.output)
        for event in ("NOTICE_ACCEPTED", "WAITING_FOR_FILE", "FILE_ARRIVED", "VERIFIED"):
            self.assertEqual(output.count("code=" + event + " "), 1)
        self.assertNotIn(str(self.shared), output)
        self.assertNotIn(self.record["approval"]["note"], output)
        self.assertNotIn(self.record["answers"]["general"]["summary"], output)

    def test_rejected_case_logs_safe_reason_without_private_original(self):
        private = "fixture-person@example.test"
        self.record["approval"]["note"] = private
        payload = (json.dumps(self.record) + "\n").encode()
        checksum = hashlib.sha256(payload).hexdigest()
        (self.shared / f"{checksum}.jsonl").write_bytes(payload)
        with self.assertLogs("doenaai.training.receiver", level="INFO") as logs:
            self.receiver.notify({"sha256": checksum, "cases": 1, "size": len(payload)})
            self.receiver.tick_all()
        output = "\n".join(logs.output)
        self.assertIn("CASE_NOT_SANITIZED", output)
        self.assertNotIn(private, output)
        self.assertNotIn(str(self.shared), output)
        self.assertEqual(self.receiver.status(checksum)["status"], "REJECTED")


if __name__ == "__main__":
    unittest.main()
