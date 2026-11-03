import asyncio
import json
from types import SimpleNamespace

import httpx
import pytest
from pydantic import ValidationError

from app.models.analysis import ProjectAnalysisRequest
from app.services.llm_service import LlmService, ModelThinkingUnsupportedError, ModelUnavailableError
from app.services.stage_checkpoints import StageCheckpoints
from tests.test_database_batches import NO_MEMBER_CHANGE
from tests.test_llm_service import (
    CONCRETE_DB_JSON, GENERAL_MODEL_JSON, REQUIREMENT_GENERAL_MODEL_JSON,
    FakeClient, analysis_request, db_only, requirement_request, settings, _enrich_internal_fixture,
)


LEGACY_FORMAT = {"template": "{%- set resolved_reasoning_effort = reasoning_effort|default('xhigh') %} low medium"}


class EffortClient(FakeClient):
    def __init__(self, show=None, **kwargs):
        super().__init__(**kwargs)
        self.show_response = show if show is not None else LEGACY_FORMAT
        self.show_calls = 0

    async def show(self, model):
        self.show_calls += 1
        if isinstance(self.show_response, Exception):
            raise self.show_response
        return self.show_response


def request_with_effort(effort, *, mode=True, requirement=False):
    request = requirement_request() if requirement else analysis_request()
    return ProjectAnalysisRequest.model_validate({
        **request.model_dump(mode="json", by_alias=True),
        "thinkingEnabled": mode, "thinkingEffort": effort,
    })


def mlx_runtime():
    selection = SimpleNamespace(model="mlx-adapter:test", digest="a" * 64)

    async def validate(selected):
        assert selected == selection

    return SimpleNamespace(selection=lambda: selection, validate_selection=validate,
                           adapters=SimpleNamespace(owns=lambda model: model == selection.model,
                                                    source_model=lambda model: "qwen3.8:27b"))


@pytest.mark.parametrize("effort", ["low", "medium", "high"])
@pytest.mark.parametrize("kind", ["native", "legacy", "mlx"])
def test_selected_strength_maps_to_the_execution_format_and_reports_the_selection(kind, effort):
    show = {"modelfile": "RENDERER qwen3.8\nPARSER qwen3.5", "template": "{{ .Prompt }}"} if kind == "native" else LEGACY_FORMAT
    client = EffortClient(show, chat_responses=[GENERAL_MODEL_JSON])
    result = asyncio.run(LlmService(settings(), client=client, runtime=mlx_runtime() if kind == "mlx" else None)
                         .analyze(request_with_effort(effort)))

    expected = True if kind == "legacy" and effort == "high" else effort
    assert client.chat_kwargs[0]["think"] == expected
    assert result.analysis_thinking_effort == effort
    assert result.model_dump(by_alias=True)["analysisThinkingEffort"] == effort
    assert "thinkingEffort" not in json.loads(client.chat_kwargs[0]["messages"][1]["content"].split("\n", 1)[1])
    assert client.show_calls == (1 if kind != "mlx" and effort == "high" else 0)


@pytest.mark.parametrize("effort", ["low", "medium", "high"])
def test_strength_is_pinned_through_validation_retries_database_batches_and_merge(effort):
    client = EffortClient(chat_responses=[
        "not-json", REQUIREMENT_GENERAL_MODEL_JSON,
        db_only(CONCRETE_DB_JSON), NO_MEMBER_CHANGE, db_only(CONCRETE_DB_JSON),
    ])
    result = asyncio.run(LlmService(settings(ai_db_batch_max_tables=1), client=client)
                         .analyze(request_with_effort(effort, requirement=True)))
    assert client.chat_calls == 5
    assert all(call["think"] == (True if effort == "high" else effort) for call in client.chat_kwargs)
    assert result.analysis_thinking_effort == effort


@pytest.mark.parametrize("mode", [False, None])
def test_disabled_or_legacy_mode_does_not_apply_a_selected_strength(mode):
    client = EffortClient(show=httpx.ConnectError("가상 연결 실패"), chat_responses=[GENERAL_MODEL_JSON])
    result = asyncio.run(LlmService(settings(), client=client).analyze(request_with_effort("high", mode=mode)))
    assert client.show_calls == 0
    assert client.chat_kwargs[0].get("think") is mode
    assert "analysisThinkingEffort" not in result.model_dump(by_alias=True)


@pytest.mark.parametrize("show", [
    {}, {"modelfile": "RENDERER unknown"}, {"template": "reasoning_effort low medium"},
    {"thinking": {"values": [False, "low", "medium", "xhigh"]}},
])
def test_unverified_high_strength_is_rejected_without_silent_default(show):
    client = EffortClient(show, chat_responses=[GENERAL_MODEL_JSON])
    with pytest.raises(ModelThinkingUnsupportedError):
        asyncio.run(LlmService(settings(), client=client).analyze(request_with_effort("high")))
    assert client.chat_calls == 0


def test_model_metadata_failure_does_not_guess_a_high_strength():
    client = EffortClient(httpx.ConnectError("가상 연결 실패"))
    with pytest.raises(ModelUnavailableError):
        asyncio.run(LlmService(settings(), client=client).analyze(request_with_effort("high")))
    assert client.chat_calls == 0


def test_other_models_require_declared_strength_support():
    client = EffortClient({"thinking": {"values": [False, "low", "medium", "high"]}}, chat_responses=[GENERAL_MODEL_JSON])
    result = asyncio.run(LlmService(settings(ai_model="another-model"), client=client).analyze(request_with_effort("low")))
    assert client.chat_kwargs[0]["think"] == "low"
    assert result.analysis_thinking_effort == "low"

    client = EffortClient({"capabilities": ["completion", "thinking"]})
    with pytest.raises(ModelThinkingUnsupportedError):
        asyncio.run(LlmService(settings(ai_model="another-model"), client=client).analyze(request_with_effort("medium")))
    assert client.chat_calls == 0


@pytest.mark.parametrize("effort", ["", "LOW", "xhigh", "max", 1, True, [], {}])
def test_invalid_strength_is_rejected_before_execution(effort):
    with pytest.raises(ValidationError):
        request_with_effort(effort)


def test_checkpoints_never_share_results_between_strengths(tmp_path):
    store = StageCheckpoints(tmp_path / "stages", "test-only-signing-key")
    for effort, expected_calls in [("low", 1), ("medium", 1), ("high", 1), ("low", 0), ("medium", 0), ("high", 0)]:
        client = EffortClient(chat_responses=[GENERAL_MODEL_JSON], list_response={"models": [{"model": "qwen3.8:27b", "digest": "a" * 64}]})
        service = LlmService(settings(), client=client, checkpoints=store)
        service._request_scope = lambda request: "same-evidence-scope"
        result = asyncio.run(service.analyze(request_with_effort(effort)))
        assert client.chat_calls == expected_calls
        assert result.analysis_thinking_effort == effort
    assert len(list(store.directory.glob("*.json"))) == 3


def test_concurrent_strengths_do_not_leak_between_requests_or_later_calls():
    async def scenario():
        started = asyncio.Event()
        calls = {}

        class InterleavedClient(EffortClient):
            async def chat(self, **kwargs):
                name = asyncio.current_task().get_name()
                calls.setdefault(name, []).append(kwargs)
                if len(calls) == 3:
                    started.set()
                await asyncio.wait_for(started.wait(), 1)
                content = REQUIREMENT_GENERAL_MODEL_JSON if len(calls[name]) == 1 else db_only(CONCRETE_DB_JSON)
                return SimpleNamespace(message=SimpleNamespace(content=_enrich_internal_fixture(content, kwargs)))

        service = LlmService(settings(), client=InterleavedClient())
        results = await asyncio.gather(*[
            asyncio.create_task(service.analyze(request_with_effort(effort, requirement=True)), name=effort)
            for effort in ("low", "medium", "high")
        ])
        assert [result.analysis_thinking_effort for result in results] == ["low", "medium", "high"]
        for effort, entries in calls.items():
            assert len(entries) == 2
            assert all(call["think"] == (True if effort == "high" else effort) for call in entries)
        assert service._request_thinking.get() is None

    asyncio.run(scenario())
