import asyncio
import copy
import json
import logging
from types import SimpleNamespace

import pytest
from pydantic import BaseModel, Field

from app.services.llm_service import LlmService
from app.services.stage_checkpoints import StageCheckpoints
from tests.test_llm_service import settings


class TraceResponse(BaseModel):
    """요청 기록과 항목별 재시도를 확인하는 작은 응답 계약이다."""
    summary: str = Field(min_length=1)
    unchanged: str


def request_records(caplog):
    return [record for record in caplog.records
            if record.name == "app.services.llm_service" and record.getMessage().startswith("model_request_json ")]


def logged_request(record):
    header, body = record.getMessage().split("\n", 1)
    return header, body, json.loads(body)


class ObservingClient:
    """응답을 반환하기 전에 전송 로그와 실제 호출 인수가 같은지 확인한다."""

    def __init__(self, caplog, responses, *, enabled=True, model="qwen3.8:27b"):
        self.caplog = caplog
        self.responses = list(responses)
        self.enabled = enabled
        self.model = model
        self.calls = []

    async def list(self):
        return {"models": [{"model": self.model, "digest": "a" * 64}]}

    async def chat(self, **kwargs):
        self.calls.append(copy.deepcopy(kwargs))
        records = request_records(self.caplog)
        if self.enabled:
            assert len(records) == len(self.calls)
            assert logged_request(records[-1])[2] == kwargs
        else:
            assert records == []
        content = self.responses.pop(0)
        return SimpleNamespace(message=SimpleNamespace(content=content, thinking="응답에만 있는 비공개 추론 표식입니다."))


class MemoryCheckpoints:
    """검증된 결과의 재사용을 파일 없이 확인하는 저장소 대역이다."""
    key = staticmethod(StageCheckpoints.key)

    def __init__(self):
        self.saved = {}

    def read(self, key):
        return copy.deepcopy(self.saved.get(key))

    def write(self, key, result):
        self.saved[key] = copy.deepcopy(result)
        return True


def analysis_messages():
    return [
        {"role": "system", "content": "전달한 자료를 분석하고 지정한 JSON 응답을 작성하세요."},
        {"role": "user", "content": '요청에만 있는 자료 표식입니다.\n{"component":"src/Service.java","label":"따옴표를 보존합니다."}'},
    ]


@pytest.mark.parametrize("think", [None, False, "medium"])
def test_enabled_request_trace_exists_before_chat_response_and_matches_actual_kwargs(caplog, think):
    config = settings(ai_log_model_requests=True, ai_keep_alive="13m")
    response = {"summary": "응답 본문에만 있는 결과 표식입니다.", "unchanged": "응답 결과를 보존합니다."}
    client = ObservingClient(caplog, [json.dumps(response, ensure_ascii=False)], model=config.ai_model)
    service = LlmService(config, client=client)
    messages = analysis_messages()
    original = copy.deepcopy(messages)

    async def scenario():
        token = service._request_thinking.set(think)
        try:
            return await service._generate(messages, TraceResponse, checkpoint_event=("DATABASE_BATCH_REUSED", 3))
        finally:
            service._request_thinking.reset(token)

    with caplog.at_level(logging.INFO, logger="app.services.llm_service"):
        result = asyncio.run(scenario())

    assert result.model_dump() == response
    records = request_records(caplog)
    assert len(records) == 1 and records[0].levelno == logging.INFO
    header, pretty_body, logged = logged_request(records[0])
    assert f"model={config.ai_model}" in header
    assert "response_type=TraceResponse" in header and "attempt=1" in header
    assert "stage=DATABASE_BATCH" in header and "index=3" in header
    assert logged == client.calls[0]
    assert logged["model"] == config.ai_model
    assert logged["messages"] == original
    assert logged["format"] == TraceResponse.model_json_schema(by_alias=True)
    assert logged["options"] == {"temperature": 0}
    assert logged["stream"] is False and logged["keep_alive"] == "13m"
    assert ("think" in logged) is (think is not None)
    if think is not None:
        assert logged["think"] == think
    assert pretty_body == json.dumps(logged, ensure_ascii=False, indent=2)
    assert messages == original
    assert response["summary"] not in caplog.text
    assert "응답에만 있는 비공개 추론 표식" not in caplog.text


def test_targeted_retries_trace_the_changed_messages_and_schema_for_each_actual_attempt(caplog):
    config = settings(ai_log_model_requests=True, ai_requirement_fast_path_enabled=True)
    first = {"summary": "", "unchanged": "이 값은 다시 생성하지 않습니다."}
    patch = {"summary": "검증된 설명으로 수정했습니다."}
    client = ObservingClient(caplog, [json.dumps(first, ensure_ascii=False), json.dumps(patch, ensure_ascii=False)], model=config.ai_model)
    service = LlmService(config, client=client)
    messages = analysis_messages()
    original = copy.deepcopy(messages)

    with caplog.at_level(logging.INFO, logger="app.services.llm_service"):
        result = asyncio.run(service._generate(messages, TraceResponse, checkpoint_event=("GENERAL_REUSED", None)))

    assert result.summary == patch["summary"] and result.unchanged == first["unchanged"]
    records = request_records(caplog)
    assert len(records) == 2
    first_header, _, first_request = logged_request(records[0])
    second_header, _, second_request = logged_request(records[1])
    assert "attempt=1" in first_header and "attempt=2" in second_header
    assert all("stage=GENERAL" in header and "index=None" in header for header in (first_header, second_header))
    assert first_request == client.calls[0] and second_request == client.calls[1]
    assert first_request["messages"] == original
    assert second_request["messages"][:len(original)] == original
    assert len(second_request["messages"]) == len(original) + 1
    repair = json.loads(second_request["messages"][-1]["content"])
    assert repair["fieldsToReplace"] == ["summary"] and repair["previousOutput"] == first
    assert set(first_request["format"]["properties"]) == {"summary", "unchanged"}
    assert set(second_request["format"]["properties"]) == {"summary"}
    assert second_request["format"]["required"] == ["summary"]
    assert messages == original


def test_disabled_request_trace_never_outputs_the_request_body(caplog):
    config = settings(ai_log_model_requests=False)
    client = ObservingClient(caplog, [json.dumps({"summary": "정상 응답입니다.", "unchanged": "유지합니다."})], enabled=False, model=config.ai_model)
    service = LlmService(config, client=client)

    with caplog.at_level(logging.INFO, logger="app.services.llm_service"):
        result = asyncio.run(service._generate(analysis_messages(), TraceResponse))

    assert result.summary == "정상 응답입니다."
    assert len(client.calls) == 1 and request_records(caplog) == []
    assert "요청에만 있는 자료 표식" not in caplog.text
    assert "src/Service.java" not in caplog.text
    assert "응답에만 있는 비공개 추론 표식" not in caplog.text


def test_reusing_a_valid_checkpoint_does_not_log_a_model_request_that_was_not_sent(caplog):
    config = settings(ai_log_model_requests=True)
    store = MemoryCheckpoints()
    body = {"summary": "저장된 검증 결과입니다.", "unchanged": "저장한 값을 재사용합니다."}
    first = ObservingClient(caplog, [json.dumps(body, ensure_ascii=False)], model=config.ai_model)
    service = LlmService(config, client=first, checkpoints=store)

    with caplog.at_level(logging.INFO, logger="app.services.llm_service"):
        initial = asyncio.run(service._generate(analysis_messages(), TraceResponse,
                                               checkpoint_scope="same-input-scope", checkpoint_event=("GENERAL_REUSED", None)))
    assert len(first.calls) == 1 and len(request_records(caplog)) == 1 and len(store.saved) == 1
    caplog.clear()
    resumed = ObservingClient(caplog, [], model=config.ai_model)
    service = LlmService(config, client=resumed, checkpoints=store)
    events = []

    async def progress(code, index):
        events.append((code, index))

    with caplog.at_level(logging.INFO, logger="app.services.llm_service"):
        result = asyncio.run(service._generate(analysis_messages(), TraceResponse, progress=progress,
                                              checkpoint_scope="same-input-scope", checkpoint_event=("GENERAL_REUSED", None)))

    assert result == initial and result.model_dump() == body
    assert resumed.calls == [] and request_records(caplog) == []
    assert events == [("GENERAL_REUSED", None)]
    assert "model_stage_reused" in caplog.text
    assert "요청에만 있는 자료 표식" not in caplog.text


def test_request_trace_formatting_failure_does_not_prevent_the_actual_model_call(caplog, monkeypatch):
    config = settings(ai_log_model_requests=True)
    client = ObservingClient(caplog, [json.dumps({"summary": "정상 응답입니다.", "unchanged": "원래 요청을 유지합니다."})], enabled=False, model=config.ai_model)
    service = LlmService(config, client=client)
    messages = analysis_messages()
    before = copy.deepcopy(messages)

    def fail_to_format(*args, **kwargs):
        raise ValueError("포매터 오류 본문 표식은 기록하지 않습니다.")

    monkeypatch.setattr("app.services.llm_service.request_log_json", fail_to_format)
    with caplog.at_level(logging.INFO, logger="app.services.llm_service"):
        result = asyncio.run(service._generate(messages, TraceResponse))

    assert result.summary == "정상 응답입니다."
    assert len(client.calls) == 1 and client.calls[0]["messages"] == before
    assert "model_request_json_skipped" in caplog.text
    assert "포매터 오류 본문 표식" not in caplog.text
    assert request_records(caplog) == [] and messages == before
