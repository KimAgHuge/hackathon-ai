import asyncio
from types import SimpleNamespace

import httpx
import pytest

from app.config import Settings
from app.models.analysis import ProjectAnalysisRequest
from app.services.llm_service import (
    InvalidModelResponseError,
    LlmService,
    ModelTimeoutError,
    ModelUnavailableError,
)


def settings(**overrides):
    values = {
        "ai_internal_api_key": "test-internal-api-key-at-least-32-characters",
        "ai_max_validation_retries": 1,
    }
    values.update(overrides)
    return Settings(**values)


def analysis_request():
    return ProjectAnalysisRequest.model_validate(
        {
            "projectId": "00000000-0000-0000-0000-000000000000",
            "sourceSummary": {"frameworks": ["Vue", "Spring Boot"]},
            "databaseSummary": {"type": "ORACLE"},
            "relations": [],
        }
    )


class FakeClient:
    def __init__(self, chat_responses=None, list_response=None, exception=None):
        self.chat_responses = list(chat_responses or [])
        self.list_response = list_response
        self.exception = exception
        self.chat_calls = 0

    async def chat(self, **kwargs):
        self.chat_calls += 1
        if self.exception:
            raise self.exception
        return SimpleNamespace(
            message=SimpleNamespace(content=self.chat_responses.pop(0))
        )

    async def list(self):
        if self.exception:
            raise self.exception
        return self.list_response


VALID_MODEL_JSON = """{
  "summary": "프로젝트 구조 요약",
  "frameworks": ["Vue", "Spring Boot", "Oracle"],
  "riskLevel": "UNKNOWN",
  "riskScore": 0,
  "impactAreas": [],
  "evidences": [],
  "recommendations": []
}"""


def test_analyze_validates_structured_response():
    client = FakeClient(chat_responses=[VALID_MODEL_JSON])
    service = LlmService(settings(), client=client)

    result = asyncio.run(service.analyze(analysis_request()))

    assert result.risk_level == "UNKNOWN"
    assert result.risk_score == 0
    assert client.chat_calls == 1


def test_analyze_retries_invalid_json_then_succeeds():
    client = FakeClient(chat_responses=["not-json", VALID_MODEL_JSON])
    service = LlmService(settings(), client=client)

    result = asyncio.run(service.analyze(analysis_request()))

    assert result.summary == "프로젝트 구조 요약"
    assert client.chat_calls == 2


def test_analyze_rejects_invalid_json_after_retry_limit():
    client = FakeClient(chat_responses=["not-json", "{}"])
    service = LlmService(settings(), client=client)

    with pytest.raises(InvalidModelResponseError):
        asyncio.run(service.analyze(analysis_request()))


def test_analyze_maps_ollama_timeout():
    request = httpx.Request("POST", "http://127.0.0.1:11434/api/chat")
    client = FakeClient(exception=httpx.ReadTimeout("timeout", request=request))
    service = LlmService(settings(), client=client)

    with pytest.raises(ModelTimeoutError):
        asyncio.run(service.analyze(analysis_request()))


def test_health_rejects_missing_model():
    client = FakeClient(list_response={"models": [{"model": "other:latest"}]})
    service = LlmService(settings(), client=client)

    with pytest.raises(ModelUnavailableError):
        asyncio.run(service.health())
