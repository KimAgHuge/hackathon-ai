import asyncio
import json

from tests.conftest import TEST_API_KEY
from app.routers import analysis as analysis_router
from app.services.llm_service import (
    InvalidModelResponseError,
    ModelTimeoutError,
    ModelUnavailableError,
)

VALID_REQUEST = {
    "projectId": "00000000-0000-0000-0000-000000000000",
    "sourceSummary": {
        "frameworks": ["Vue", "Spring Boot"],
        "components": [],
    },
    "databaseSummary": {
        "type": "ORACLE",
        "tables": [],
    },
    "relations": [],
}


def test_analysis_requires_internal_api_key(client, fake_llm_service):
    response = client.post("/api/analysis/project", json=VALID_REQUEST)

    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "INVALID_INTERNAL_API_KEY"
    assert fake_llm_service.analysis_calls == 0


def test_analysis_returns_camel_case_response(client, fake_llm_service):
    response = client.post(
        "/api/analysis/project",
        json=VALID_REQUEST,
        headers={"X-Internal-Api-Key": TEST_API_KEY},
    )

    assert response.status_code == 200
    assert response.json()["riskLevel"] == "UNKNOWN"
    assert response.json()["riskScore"] == 0
    assert fake_llm_service.analysis_calls == 1


def test_analysis_rejects_sensitive_fields_before_model_call(
    client,
    fake_llm_service,
):
    request_with_secret = {
        **VALID_REQUEST,
        "databaseSummary": {
            "type": "ORACLE",
            "password": "must-not-reach-model",
        },
    }

    response = client.post(
        "/api/analysis/project",
        json=request_with_secret,
        headers={"X-Internal-Api-Key": TEST_API_KEY},
    )

    assert response.status_code == 422
    assert "must-not-reach-model" not in response.text
    assert fake_llm_service.analysis_calls == 0


def test_analysis_maps_ollama_unavailable_to_503(client, fake_llm_service):
    fake_llm_service.analysis_exception = ModelUnavailableError(
        "Ollama is unavailable."
    )

    response = client.post(
        "/api/analysis/project",
        json=VALID_REQUEST,
        headers={"X-Internal-Api-Key": TEST_API_KEY},
    )

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "MODEL_UNAVAILABLE"


def test_analysis_maps_timeout_to_504(client, fake_llm_service):
    fake_llm_service.analysis_exception = ModelTimeoutError(
        "Ollama inference timed out."
    )

    response = client.post(
        "/api/analysis/project",
        json=VALID_REQUEST,
        headers={"X-Internal-Api-Key": TEST_API_KEY},
    )

    assert response.status_code == 504
    assert response.json()["detail"]["code"] == "MODEL_TIMEOUT"


def test_analysis_maps_invalid_model_response_to_502(client, fake_llm_service):
    fake_llm_service.analysis_exception = InvalidModelResponseError(
        "The model response did not match the required schema."
    )

    response = client.post(
        "/api/analysis/project",
        json=VALID_REQUEST,
        headers={"X-Internal-Api-Key": TEST_API_KEY},
    )

    assert response.status_code == 502
    assert response.json()["detail"]["code"] == "INVALID_MODEL_RESPONSE"


def test_stream_requires_key_and_emits_only_safe_progress_and_final_result(client, fake_llm_service):
    assert client.post("/api/analysis/project/stream", json=VALID_REQUEST).status_code == 401
    response = client.post(
        "/api/analysis/project/stream", json=VALID_REQUEST,
        headers={"X-Internal-Api-Key": TEST_API_KEY},
    )
    events = [json.loads(line) for line in response.text.splitlines()]
    assert response.status_code == 200
    assert [event["code"] for event in events[:-1]] == [
        "GENERAL_STARTED", "GENERAL_COMPLETED", "DATABASE_SKIPPED",
    ]
    assert events[-1]["type"] == "result"
    assert events[-1]["result"]["plainLanguageSummary"]
    assert "test-internal-api-key" not in response.text


def test_stream_emits_safe_error_code_without_exception_message(client, fake_llm_service):
    fake_llm_service.analysis_exception = ModelUnavailableError("token=must-not-leak")
    response = client.post(
        "/api/analysis/project/stream", json=VALID_REQUEST,
        headers={"X-Internal-Api-Key": TEST_API_KEY},
    )
    events = [json.loads(line) for line in response.text.splitlines()]
    assert events[-1] == {"type": "error", "code": "MODEL_UNAVAILABLE"}
    assert "must-not-leak" not in response.text


def test_stream_keeps_connection_open_during_model_wait(client, fake_llm_service, monkeypatch):
    monkeypatch.setattr(analysis_router, "STREAM_HEARTBEAT_SECONDS", 0.01)
    original_analyze = fake_llm_service.analyze

    async def delayed_analyze(request, progress=None):
        await asyncio.sleep(0.04)
        return await original_analyze(request, progress=progress)

    monkeypatch.setattr(fake_llm_service, "analyze", delayed_analyze)
    response = client.post(
        "/api/analysis/project/stream", json=VALID_REQUEST,
        headers={"X-Internal-Api-Key": TEST_API_KEY},
    )

    events = [json.loads(line) for line in response.text.splitlines()]
    assert response.status_code == 200
    assert any(event["type"] == "heartbeat" for event in events)
    assert events[-1]["type"] == "result"
