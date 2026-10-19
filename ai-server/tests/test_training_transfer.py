"""검토 사례 게시 후 AI 학습 서버 알림 실패와 수신증 재확인을 검사한다."""

import json
import sys
import threading
from http.server import ThreadingHTTPServer
from pathlib import Path
from uuid import uuid4
import copy

from tests.conftest import TEST_API_KEY

import httpx
import pytest
from pydantic import SecretStr

from app.config import Settings
from app.services.training_transfer_service import TrainingTransferService

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "training/tests"))
from test_data_tools import case
from receiver_service import ReceiverStore, make_handler


def test_notice_failure_preserves_file_and_retry_verifies(tmp_path):
    job_id = uuid4()
    record = case()
    record["caseId"] = f"analysis-{job_id}"
    (tmp_path / "shared").mkdir()
    settings = Settings(
        ai_internal_api_key="synthetic-ai-key-long-enough-for-test",
        training_shared_dir=str(tmp_path / "shared"),
        training_receiver_url="http://127.0.0.1:8011",
        training_transfer_key=SecretStr("synthetic-transfer-key-long-enough-for-test"),
    )
    calls = []

    def handler(request):
        calls.append(request)
        if len(calls) == 1:
            raise httpx.ConnectError("synthetic connection failure")
        if request.method == "POST":
            return httpx.Response(202, json={"status": "WAITING_FOR_FILE"})
        notice = json.loads(calls[-2].content)
        return httpx.Response(200, json={**notice, "status": "VERIFIED"})

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        service = TrainingTransferService(settings, outbox=tmp_path / "outbox",
                                          status_dir=tmp_path / "status", client=client)
        first = service.publish(job_id, record)
        assert first["status"] == "WAITING_FOR_RECEIVER"
        assert (tmp_path / "shared" / f'{first["sha256"]}.jsonl').is_file()
        service.retry_pending()
        assert service.by_job(job_id)["status"] == "VERIFIED"
        assert len(list((tmp_path / "shared").glob("*.jsonl"))) == 1
        assert calls[1].headers["x-transfer-signature"]


def test_studio_to_macbook_receiver_over_local_http(tmp_path):
    secret = "synthetic-transfer-key-long-enough-for-test"
    shared = tmp_path / "shared"
    shared.mkdir()
    receiver = ReceiverStore(shared, tmp_path / "receiver-state",
                             tmp_path / "staging", tmp_path / "inbox")
    try:
        server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(receiver, secret))
    except PermissionError:
        pytest.skip("현재 실행 환경에서 로컬 시험 포트 바인딩을 허용하지 않습니다.")
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        job_id = uuid4()
        record = case()
        record["caseId"] = f"analysis-{job_id}"
        settings = Settings(
            ai_internal_api_key="synthetic-ai-key-long-enough-for-test",
            training_shared_dir=str(shared),
            training_receiver_url=f"http://127.0.0.1:{server.server_port}",
            training_transfer_key=SecretStr(secret),
        )
        service = TrainingTransferService(settings, outbox=tmp_path / "outbox",
                                          status_dir=tmp_path / "studio-status")
        status = service.publish(job_id, record)
        assert status["status"] == "WAITING_FOR_SYNC"
        assert receiver.intake_status()["status"] == "WAITING_FOR_DATA"
        receiver.tick_all()
        assert service.by_job(job_id)["status"] == "VERIFIED"
        assert receiver.intake_status() == {"status": "READY_FOR_DATASET", "files": 1, "cases": 1}
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=2)


def test_validation_only_needs_no_transfer_configuration(client):
    job_id = uuid4()
    record = case()
    record["caseId"] = f"analysis-{job_id}"
    response = client.post("/api/training/transfers/validate", headers={"X-Internal-Api-Key": TEST_API_KEY},
                           json={"job_id": str(job_id), "case_draft": record})
    assert response.status_code == 200
    assert response.json() == {"valid": True}


def test_validation_reports_grounding_code_without_original_text(client, caplog):
    job_id = uuid4()
    record = case()
    record["caseId"] = f"analysis-{job_id}"
    record["answers"]["general"]["summary"] = "private-customer-value"
    record["answers"]["general"]["evidences"] = [{"file": "src/project.py", "line": 999}]
    response = client.post("/api/training/transfers/validate", headers={"X-Internal-Api-Key": TEST_API_KEY},
                           json={"job_id": str(job_id), "case_draft": record})
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "GROUNDING_MISMATCH"
    assert response.json()["detail"]["field"] == "answers.general"
    assert "private-customer-value" not in response.text
    assert "private-customer-value" not in caplog.text
    assert "GROUNDING_MISMATCH" in caplog.text


def test_verified_supplement_is_part_of_training_input_and_passes_same_validator(client):
    job_id = uuid4()
    record = case()
    record["caseId"] = f"analysis-{job_id}"
    record["answers"]["general"]["evidences"] = [{"file": "src/project.py", "line": 999}]
    original = copy.deepcopy(record)
    record["request"]["sourceSummary"]["reviewedEvidence"] = [{
        "file": "src/project.py", "line": 999, "sourceRevision": "a" * 40,
        "excerpt": "확인한 소스 조각", "origin": "VERIFIED_GIT_SNAPSHOT",
    }]
    response = client.post("/api/training/transfers/validate", headers={"X-Internal-Api-Key": TEST_API_KEY},
                           json={"job_id": str(job_id), "case_draft": record})
    assert response.status_code == 200
    assert "reviewedEvidence" not in original["request"]["sourceSummary"]


def test_validation_is_authenticated_and_sanitization_is_not_bypassed(client):
    job_id = uuid4()
    record = case()
    record["caseId"] = f"analysis-{job_id}"
    request = {"job_id": str(job_id), "case_draft": record}
    assert client.post("/api/training/transfers/validate", json=request).status_code == 401
    record["approval"]["note"] = "private@example.test"
    response = client.post("/api/training/transfers/validate", headers={"X-Internal-Api-Key": TEST_API_KEY}, json=request)
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "CASE_NOT_SANITIZED"
    assert "private@example.test" not in response.text
