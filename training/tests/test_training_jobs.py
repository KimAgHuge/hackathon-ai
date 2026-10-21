"""소켓을 열지 않고 학습 작업 API의 서명 검사와 안전한 응답을 확인한다."""

import io
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from dataset_contract import VALIDATION_FEATURES
from receiver_service import CAPABILITIES_PATH, INTAKE_PATH, make_handler
from shared_transfer import signed_headers
from training_jobs import TrainingJobError


class TrainingJobApiTests(unittest.TestCase):
    """외부 연결이나 실제 학습 없이 요청 전달과 정보 노출 차단을 검증한다."""

    def setUp(self):
        self.secret = "synthetic-training-key-for-http-tests-only"
        self.job_id = "1c63d5be-5f69-4cca-8aee-8e34a983471a"
        self.job = {"id": self.job_id, "status": "PENDING"}
        self.receiver = Mock()
        self.jobs = Mock()
        self.jobs.capabilities.return_value = {
            "trainingExecution": True, "trainingExecutionReason": "학습 작업을 요청할 수 있습니다."}
        self.jobs.submit.return_value = self.job
        self.jobs.get_job.return_value = self.job
        self.jobs.cancel.return_value = {**self.job, "status": "CANCELLED"}
        self.jobs.list_jobs.return_value = {"jobs": [self.job], "limit": 100, "truncated": False}

    def request(self, method, path, value=None, *, authorized=True, jobs=True, raw_body=None):
        """핸들러의 입출력만 메모리 객체로 바꾸고 실제 서명 검사는 유지한다."""
        body = raw_body if raw_body is not None else (
            json.dumps(value, ensure_ascii=False).encode() if value is not None else b"")
        handler_type = make_handler(self.receiver, self.secret, self.jobs if jobs else None)
        handler = handler_type.__new__(handler_type)
        handler.command = method
        handler.path = path
        handler.headers = {"Content-Length": str(len(body))}
        if authorized:
            handler.headers.update(signed_headers(self.secret, method, path, body))
        handler.rfile = io.BytesIO(body)
        responses = []
        handler._send = lambda status, response: responses.append((status, response))
        getattr(handler, "do_" + method)()
        self.assertEqual(len(responses), 1)
        return responses[0]

    def test_training_routes_require_signature_before_accessing_jobs(self):
        routes = [("GET", "/api/training/jobs", None),
                  ("GET", f"/api/training/jobs/{self.job_id}", None),
                  ("POST", "/api/training/jobs", {"jobId": self.job_id}),
                  ("POST", f"/api/training/jobs/{self.job_id}/cancel", {})]
        for method, path, value in routes:
            with self.subTest(method=method, path=path):
                self.assertEqual(self.request(method, path, value, authorized=False)[0], 401)
        self.assertEqual(self.jobs.mock_calls, [])

    def test_submit_returns_accepted_job_and_preserves_request_body(self):
        value = {"jobId": self.job_id, "iterations": 3}
        self.assertEqual(self.request("POST", "/api/training/jobs", value), (202, self.job))
        self.jobs.submit.assert_called_once_with(value)

    def test_list_and_detail_return_only_service_results(self):
        self.assertEqual(self.request("GET", "/api/training/jobs"),
                         (200, self.jobs.list_jobs.return_value))
        self.assertEqual(self.request("GET", f"/api/training/jobs/{self.job_id}"), (200, self.job))
        self.jobs.list_jobs.assert_called_once_with()
        self.jobs.get_job.assert_called_once_with(self.job_id)

    def test_missing_job_returns_not_found(self):
        self.jobs.get_job.return_value = None
        status, value = self.request("GET", f"/api/training/jobs/{self.job_id}")
        self.assertEqual(status, 404)
        self.assertEqual(value["code"], "NOT_FOUND")

    def test_cancel_returns_updated_job_without_starting_another_job(self):
        value = {}
        self.assertEqual(self.request("POST", f"/api/training/jobs/{self.job_id}/cancel", value),
                         (200, self.jobs.cancel.return_value))
        self.jobs.cancel.assert_called_once_with(self.job_id, value)
        self.jobs.submit.assert_not_called()

    def test_capabilities_keep_existing_validation_contract(self):
        status, value = self.request("GET", CAPABILITIES_PATH)
        self.assertEqual(status, 200)
        self.assertEqual(value["validationVersion"], 2)
        self.assertEqual(value["features"], list(VALIDATION_FEATURES))
        self.assertTrue(value["trainingExecution"])
        self.assertEqual(value["trainingExecutionReason"],
                         self.jobs.capabilities.return_value["trainingExecutionReason"])

    def test_capabilities_without_job_store_explicitly_disable_execution(self):
        status, value = self.request("GET", CAPABILITIES_PATH, jobs=False)
        self.assertEqual(status, 200)
        self.assertEqual(value["features"], list(VALIDATION_FEATURES))
        self.assertIs(value["trainingExecution"], False)
        self.assertIsInstance(value["trainingExecutionReason"], str)
        self.assertTrue(value["trainingExecutionReason"].strip())

    def test_existing_transfer_and_intake_routes_still_use_receiver(self):
        checksum = "a" * 64
        notice = {"sha256": checksum, "cases": 1, "size": 123}
        self.receiver.notify.return_value = {**notice, "status": "WAITING_FOR_FILE"}
        self.receiver.status.return_value = {**notice, "status": "VERIFIED"}
        self.receiver.intake_status.return_value = {"status": "READY_FOR_DATASET", "files": 1, "cases": 1}
        self.assertEqual(self.request("POST", "/api/training/transfers", notice),
                         (202, self.receiver.notify.return_value))
        self.assertEqual(self.request("GET", f"/api/training/transfers/{checksum}"),
                         (200, self.receiver.status.return_value))
        self.assertEqual(self.request("GET", INTAKE_PATH), (200, self.receiver.intake_status.return_value))
        self.receiver.notify.assert_called_once_with(notice)
        self.receiver.status.assert_called_once_with(checksum)
        self.receiver.intake_status.assert_called_once_with()
        self.assertEqual(self.jobs.mock_calls, [])

    def test_invalid_json_does_not_reach_job_store_or_echo_raw_input(self):
        private = "synthetic-private-original-value"
        status, value = self.request("POST", "/api/training/jobs", raw_body=("{" + private).encode())
        self.assertEqual(status, 422)
        self.assertNotIn(private, json.dumps(value))
        self.jobs.submit.assert_not_called()

    def test_known_error_uses_declared_status_and_safe_code(self):
        self.jobs.submit.side_effect = TrainingJobError("TRAINING_UNAVAILABLE", "학습 실행이 꺼져 있습니다.", 409)
        status, value = self.request("POST", "/api/training/jobs", {"jobId": self.job_id})
        self.assertEqual(status, 409)
        self.assertEqual(value["code"], "TRAINING_UNAVAILABLE")
        self.assertNotIn("Traceback", json.dumps(value))

    def test_unexpected_error_never_echoes_exception_details(self):
        private = "synthetic-private-original-value"
        self.jobs.submit.side_effect = RuntimeError(private)
        status, value = self.request("POST", "/api/training/jobs", {"jobId": self.job_id})
        self.assertEqual(status, 500)
        self.assertEqual(value["code"], "TRAINING_INTERNAL_ERROR")
        self.assertNotIn(private, json.dumps(value))
        self.assertNotIn("Traceback", json.dumps(value))

    def test_missing_job_store_lists_no_jobs_and_refuses_execution(self):
        self.assertEqual(self.request("GET", "/api/training/jobs", jobs=False),
                         (200, {"jobs": [], "limit": 100, "truncated": False}))
        status, value = self.request("GET", f"/api/training/jobs/{self.job_id}", jobs=False)
        self.assertEqual(status, 404)
        self.assertEqual(value["code"], "NOT_FOUND")
        for path in ("/api/training/jobs", f"/api/training/jobs/{self.job_id}/cancel"):
            with self.subTest(path=path):
                status, value = self.request("POST", path, {"jobId": self.job_id}, jobs=False)
                self.assertEqual(status, 503)
                self.assertEqual(value["code"], "TRAINING_UNAVAILABLE")


if __name__ == "__main__":
    unittest.main()
