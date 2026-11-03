import asyncio
import json
from types import SimpleNamespace

import httpx
import pytest
from pydantic import ValidationError

from app.models.analysis import ProjectAnalysisRequest, RequirementGeneralAnalysisResponse
from app.services.llm_service import LlmService, ModelThinkingUnsupportedError, ModelTimeoutError
from app.services.stage_checkpoints import StageCheckpoints
from ollama import ResponseError
from tests.test_database_batches import NO_MEMBER_CHANGE, checkpoint_client
from tests.test_llm_service import (
    CONCRETE_DB_JSON,
    GENERAL_MODEL_JSON,
    REQUIREMENT_GENERAL_MODEL_JSON,
    FakeClient,
    analysis_request,
    db_only,
    requirement_request,
    settings,
    _enrich_internal_fixture,
)


def request_with_mode(mode, *, requirement=False):
    request = requirement_request() if requirement else analysis_request()
    payload = request.model_dump(mode="json", by_alias=True)
    payload["thinkingEnabled"] = mode
    return ProjectAnalysisRequest.model_validate(payload)


def assert_mode(call, mode):
    if mode is None:
        assert "think" not in call
    else:
        assert call["think"] == ("medium" if mode is True else mode)


def test_new_request_enables_thinking_when_the_field_is_omitted():
    payload = analysis_request().model_dump(mode="json", by_alias=True)
    payload.pop("thinkingEnabled")
    request = ProjectAnalysisRequest.model_validate(payload)
    client = FakeClient(chat_responses=[GENERAL_MODEL_JSON])

    result = asyncio.run(LlmService(settings(), client=client).analyze(request))

    assert request.thinking_enabled is True
    assert client.chat_kwargs[0]["think"] == "medium"
    assert result.analysis_thinking_enabled is True


@pytest.mark.parametrize("effort", ["low", "medium"])
def test_qwen_enabled_mode_keeps_the_selected_reasoning_strength(effort):
    client = FakeClient(chat_responses=[GENERAL_MODEL_JSON])
    result = asyncio.run(LlmService(settings(ai_qwen_thinking_effort=effort), client=client).analyze(analysis_request()))
    assert client.chat_kwargs[0]["think"] == effort
    assert result.analysis_thinking_enabled is True


def test_qwen_strength_rejects_high_that_legacy_gguf_templates_cannot_accept():
    with pytest.raises(ValidationError):
        settings(ai_qwen_thinking_effort="high")


def test_other_model_families_keep_boolean_thinking_without_qwen_specific_strength():
    client = FakeClient(chat_responses=[GENERAL_MODEL_JSON])
    asyncio.run(LlmService(settings(ai_model="example-model:latest"), client=client).analyze(analysis_request()))
    assert client.chat_kwargs[0]["think"] is True


def test_trained_qwen_uses_its_verified_source_for_reasoning_strength():
    async def validate_selection(selection):
        return None

    name = "doenaai-trained:web-424b376d-2419-4c80-a776-87d7d863b6d2"
    selected = SimpleNamespace(model=name, digest="a" * 64)
    runtime = SimpleNamespace(selection=lambda: selected, validate_selection=validate_selection,
                              trained=SimpleNamespace(owns=lambda model: model == name,
                                                      metadata_for=lambda model,digest: {"sourceModel":"qwen3.8:27b"}))
    client = FakeClient(chat_responses=[GENERAL_MODEL_JSON])
    result = asyncio.run(LlmService(settings(ai_model="example-model"), client=client, runtime=runtime).analyze(analysis_request()))
    assert client.chat_kwargs[0]["think"] == "medium"
    assert result.analysis_source_model == "qwen3.8:27b"
    assert result.analysis_thinking_enabled is True


@pytest.mark.parametrize("value", [0, 1, -1, 0.0, 1.0, "true", "false", "on", "off", [], {}])
def test_thinking_rejects_values_that_are_not_boolean_or_null(value):
    with pytest.raises(ValidationError) as failure:
        request_with_mode(value)

    assert any(error["loc"] == ("thinkingEnabled",) for error in failure.value.errors())


@pytest.mark.parametrize("mode", [True, False, None])
def test_mode_is_preserved_in_every_stage_and_validation_retry(mode):
    final = json.loads(REQUIREMENT_GENERAL_MODEL_JSON)
    final["summary"] = "확인한 DB 구조에는 별도 팀 연결이 없어 스키마와 이관 검토가 필요합니다."
    final["evidences"] = [{"file": "src/ProjectStore.java", "line": 3, "description": "관찰한 저장 대상"}]
    client = FakeClient(chat_responses=[
        "not-json", REQUIREMENT_GENERAL_MODEL_JSON,
        "not-json", db_only(CONCRETE_DB_JSON),
        "not-json", json.dumps(final, ensure_ascii=False),
    ])
    events = []

    async def progress(code, detail):
        events.append(code)

    result = asyncio.run(LlmService(settings(ai_final_reconciliation_enabled=True), client=client)
                         .analyze(request_with_mode(mode, requirement=True), progress))

    assert client.chat_calls == 6
    assert events.count("VALIDATION_RETRY") == 3
    assert "RECONCILIATION_COMPLETED" in events
    assert result.database_impact.status.value == "CHANGE_REQUIRED"
    assert result.summary == final["summary"]
    assert result.analysis_thinking_enabled is mode
    for call in client.chat_kwargs:
        assert_mode(call, mode)
        assert call["options"] == {"temperature": 0}


@pytest.mark.parametrize("mode", [True, False, None])
def test_mode_reaches_each_database_batch_and_merge(mode):
    client = FakeClient(chat_responses=[
        REQUIREMENT_GENERAL_MODEL_JSON,
        db_only(CONCRETE_DB_JSON),
        NO_MEMBER_CHANGE,
        db_only(CONCRETE_DB_JSON),
    ])
    events = []

    async def progress(code, detail):
        events.append((code, detail))

    result = asyncio.run(LlmService(settings(ai_db_batch_max_tables=1), client=client)
                         .analyze(request_with_mode(mode, requirement=True), progress))

    assert client.chat_calls == 4
    assert ("DATABASE_BATCH_COMPLETED", 2) in events
    assert ("DATABASE_MERGE_COMPLETED", 1) in events
    assert result.analysis_thinking_enabled is mode
    for call in client.chat_kwargs:
        assert_mode(call, mode)


def test_concurrent_requests_keep_their_own_mode_and_release_it_after_completion():
    async def scenario():
        started = asyncio.Event()
        calls = {}

        class InterleavedClient:
            async def chat(self, **kwargs):
                name = asyncio.current_task().get_name()
                own_calls = calls.setdefault(name, [])
                own_calls.append(kwargs)
                if len(calls) == 2:
                    started.set()
                await asyncio.wait_for(started.wait(), 1)
                # 두 요청을 겹쳐 실행해 다음 DB 단계에도 같은 선택이 유지되는지 확인한다.
                await asyncio.sleep(0)
                content = REQUIREMENT_GENERAL_MODEL_JSON if len(own_calls) == 1 else db_only(CONCRETE_DB_JSON)
                return SimpleNamespace(message=SimpleNamespace(content=_enrich_internal_fixture(content, kwargs)))

        service = LlmService(settings(), client=InterleavedClient())

        async def analyze(mode):
            result = await service.analyze(request_with_mode(mode, requirement=True))
            assert service._request_thinking.get() is None
            return result

        results = await asyncio.gather(
            asyncio.create_task(analyze(True), name="thinking-on"),
            asyncio.create_task(analyze(False), name="thinking-off"),
        )
        assert [result.analysis_thinking_enabled for result in results] == [True, False]
        assert service._request_thinking.get() is None
        assert set(calls) == {"thinking-on", "thinking-off"}
        for name, expected in [("thinking-on", True), ("thinking-off", False)]:
            assert len(calls[name]) == 2
            for call in calls[name]:
                assert_mode(call, expected)

    asyncio.run(scenario())


@pytest.mark.parametrize("mode", [True, False])
def test_failed_request_does_not_leak_its_mode_to_later_generation(mode):
    async def scenario():
        client = FakeClient(exception=httpx.ReadTimeout("가상 추론 시간 초과"))
        service = LlmService(settings(), client=client)
        with pytest.raises(ModelTimeoutError):
            await service.analyze(request_with_mode(mode))
        assert_mode(client.chat_kwargs[0], mode)

        client.exception = None
        client.chat_responses = [REQUIREMENT_GENERAL_MODEL_JSON]
        await service._generate(
            [{"role": "user", "content": "이전 요청의 선택이 남지 않아야 합니다."}],
            RequirementGeneralAnalysisResponse,
        )
        assert "think" not in client.chat_kwargs[-1]

    asyncio.run(scenario())


def test_checkpoint_reuses_the_same_mode_but_never_a_different_mode(tmp_path):
    store = StageCheckpoints(tmp_path / "stages", "test-only-signing-key")
    messages = []
    for mode, expected_calls in [(True, 1), (True, 0), (False, 1), (False, 0), (None, 1), (None, 0), (True, 0)]:
        client = checkpoint_client([GENERAL_MODEL_JSON])
        service = LlmService(settings(), client=client, checkpoints=store)
        # 입력 근거와 범위가 같아도 생성 설정이 다르면 결과를 공유하면 안 된다.
        service._request_scope = lambda request: "same-evidence-scope"
        result = asyncio.run(service.analyze(request_with_mode(mode)))
        assert client.chat_calls == expected_calls
        assert result.analysis_thinking_enabled is mode
        if client.chat_kwargs:
            messages.append(client.chat_kwargs[0]["messages"])
            assert_mode(client.chat_kwargs[0], mode)

    assert messages[0] == messages[1] == messages[2]
    assert len(list(store.directory.glob("*.json"))) == 3


def test_legacy_default_mode_preserves_the_original_evidence_scope_key():
    request = request_with_mode(None)
    original = request.model_dump(mode="json", by_alias=True)
    original.pop("thinkingEnabled")
    original.pop("thinkingEffort")
    original["databaseSummary"].pop("capturedAt", None)
    assert LlmService._request_scope(request) == StageCheckpoints.key(original)


def test_unsupported_thinking_has_a_specific_error_and_never_silently_disables_it():
    client = FakeClient(exception=ResponseError('"example-model" does not support thinking', status_code=400))
    with pytest.raises(ModelThinkingUnsupportedError):
        asyncio.run(LlmService(settings(), client=client).analyze(request_with_mode(True)))

    assert client.chat_calls == 1
    assert client.chat_kwargs[0]["think"] == "medium"
