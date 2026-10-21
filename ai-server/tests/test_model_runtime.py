"""실제 Ollama 연결 없이 모델 전환의 인증·증거·영속성·동시성을 검사한다."""

import asyncio
from uuid import uuid4

import httpx
import pytest

from app.config import Settings
from app.dependencies import get_model_runtime_service
from app.services.model_runtime import ModelRuntimeError, ModelRuntimeService, RuntimeStore, Selection
from tests.conftest import TEST_API_KEY

BASE, TARGET, THIRD = "qwen3.8:27b", "local-test:latest", "local-other:latest"
FIRST, SECOND, THIRD_DIGEST = "a" * 64, "b" * 64, "c" * 64


class FakeAdminClient:
    def __init__(self):
        self.models = [{"name": BASE, "digest": FIRST, "size": 100}, {"name": TARGET, "digest": SECOND, "size": 200}]
        self.capabilities = {BASE: ["completion"], TARGET: ["completion"]}
        self.show_extra = {}
        self.calls = []
        self.chat_result = {"model": TARGET, "done": True, "done_reason": "stop", "message": {"content": '{"message":"모델 응답 확인"}'}}
        self.error = None
        self.delay = 0

    async def list(self):
        self.calls.append(("list", None))
        if self.error:
            raise self.error
        if self.delay:
            await asyncio.sleep(self.delay)
        return {"models": [dict(item) for item in self.models]}

    async def show(self, model):
        self.calls.append(("show", model))
        return {"capabilities": self.capabilities.get(model), **self.show_extra.get(model, {})}

    async def chat(self, **body):
        self.calls.append(("chat", body))
        return self.chat_result


@pytest.fixture
def runtime(tmp_path):
    settings = Settings(ai_internal_api_key=TEST_API_KEY, ai_model_state_file=str(tmp_path / "runtime.sqlite3"))
    return ModelRuntimeService(settings, client=FakeAdminClient())


def run(value):
    return asyncio.run(value)


def proof(runtime):
    return run(runtime.probe(TARGET, SECOND))


def activation(runtime, token=None, operation_id=None, revision=0):
    token = token or proof(runtime)["probeToken"]
    return run(runtime.activate(operation_id or uuid4(), TARGET, SECOND, token, revision))


def test_installed_only_reads_local_metadata_and_never_probes(runtime):
    result = run(runtime.installed())
    assert result["model"] == BASE
    assert result["currentDigest"] == FIRST
    assert result["currentRevision"] == 0
    assert result["models"][1]["selectable"] is True
    assert all(call[0] != "chat" for call in runtime.client.calls)
    assert runtime.settings.ai_model == BASE


@pytest.mark.parametrize("caps,extra,name", [(["embedding"], {}, TARGET), ([], {}, TARGET),
    (["completion"], {"remote_model": "private-remote"}, TARGET),
    (["completion"], {"remote_host": "https://example.invalid"}, TARGET),
    (["completion"], {}, "test:cloud")])
def test_embedding_unknown_and_cloud_models_are_not_selectable(runtime, caps, extra, name):
    runtime.client.models[1]["name"] = name
    runtime.client.capabilities[name] = caps
    runtime.client.show_extra[name] = extra
    listing = run(runtime.installed())
    assert listing["models"][1]["selectable"] is False
    with pytest.raises(ModelRuntimeError, match="로컬"):
        run(runtime.probe(name, SECOND))
    assert all(call[0] != "chat" for call in runtime.client.calls)


def test_probe_uses_fixed_bounded_korean_json_and_does_not_change_selection(runtime):
    result = proof(runtime)
    assert result["status"] == "PASSED" and result["currentRevision"] == 0
    assert len(result["probeToken"]) >= 32
    body = next(call[1] for call in runtime.client.calls if call[0] == "chat")
    assert body["stream"] is False and body["think"] is False
    assert body["options"] == {"temperature": 0, "num_predict": 128}
    assert body["format"]["additionalProperties"] is False
    assert runtime.selection().model == BASE


@pytest.mark.parametrize("change", [{"done": False}, {"done_reason": "length"}, {"model": BASE},
    {"message": {"content": "not json"}}, {"message": {"content": '{"message":"잘못된 문구"}'}},
    {"message": {"content": '{"message":"모델 응답 확인","extra":true}'}}])
def test_probe_rejects_incomplete_wrong_model_and_bad_json(runtime, change):
    runtime.client.chat_result.update(change)
    with pytest.raises(ModelRuntimeError) as caught:
        proof(runtime)
    assert caught.value.code == "MODEL_PROBE_FAILED"
    assert runtime.selection().revision == 0


def test_activation_is_atomic_persisted_and_idempotent_after_restart(runtime):
    token, operation_id = proof(runtime)["probeToken"], uuid4()
    result = activation(runtime, token, operation_id)
    assert result["status"] == "APPLIED" and result["previousModel"] == BASE and result["revision"] == 1
    assert runtime.selection() == Selection(TARGET, SECOND, 1, BASE, FIRST)
    restarted = ModelRuntimeService(runtime.settings, client=runtime.client)
    calls = len(runtime.client.calls)
    assert activation(restarted, token, operation_id) == result
    assert len(runtime.client.calls) == calls
    assert restarted.operation(operation_id) == result
    assert restarted.selection().model == TARGET


def test_same_operation_id_with_different_body_is_rejected(runtime):
    token, operation_id = proof(runtime)["probeToken"], uuid4()
    activation(runtime, token, operation_id)
    with pytest.raises(ModelRuntimeError) as caught:
        run(runtime.rollback(operation_id, 1))
    assert caught.value.code == "MODEL_OPERATION_CONFLICT"
    assert runtime.selection().revision == 1


def test_expired_evidence_is_failed_record_and_not_retried_under_same_id(runtime):
    runtime.clock = lambda: 1000
    token, operation_id = proof(runtime)["probeToken"], uuid4()
    runtime.clock = lambda: 2000
    for _ in range(2):
        with pytest.raises(ModelRuntimeError) as caught:
            activation(runtime, token, operation_id)
        assert caught.value.code == "MODEL_PROBE_REQUIRED"
    assert runtime.operation(operation_id)["status"] == "FAILED"
    assert runtime.selection().revision == 0


def test_stale_revision_and_consumed_probe_cannot_switch(runtime):
    token = proof(runtime)["probeToken"]
    activation(runtime, token)
    with pytest.raises(ModelRuntimeError) as caught:
        activation(runtime, token, revision=0)
    assert caught.value.code == "MODEL_REVISION_CHANGED"
    with pytest.raises(ModelRuntimeError) as caught:
        activation(runtime, token, revision=1)
    assert caught.value.code == "MODEL_PROBE_REQUIRED"


def test_changed_target_digest_rejects_activation_and_keeps_previous_model(runtime):
    token = proof(runtime)["probeToken"]
    runtime.client.models[1]["digest"] = THIRD_DIGEST
    with pytest.raises(ModelRuntimeError) as caught:
        activation(runtime, token)
    assert caught.value.code == "MODEL_DIGEST_CHANGED"
    assert runtime.selection().model == BASE


def test_rollback_only_restores_unchanged_previous_model_and_consumes_slot(runtime):
    activation(runtime)
    operation_id = uuid4()
    result = run(runtime.rollback(operation_id, 1))
    assert result["status"] == "ROLLED_BACK" and result["model"] == BASE and result["revision"] == 2
    assert runtime.selection() == Selection(BASE, FIRST, 2)
    assert run(runtime.rollback(operation_id, 1)) == result
    with pytest.raises(ModelRuntimeError) as caught:
        run(runtime.rollback(uuid4(), 2))
    assert caught.value.code == "MODEL_ROLLBACK_UNAVAILABLE"
    assert all(call[0] != "chat" for call in runtime.client.calls[7:])


def test_rollback_rejects_previous_model_missing_or_changed(runtime):
    activation(runtime)
    runtime.client.models[0]["digest"] = THIRD_DIGEST
    with pytest.raises(ModelRuntimeError) as caught:
        run(runtime.rollback(uuid4(), 1))
    assert caught.value.code == "MODEL_DIGEST_CHANGED"
    assert runtime.selection().model == TARGET


def test_cross_process_style_lock_rejects_overlapping_transition(runtime):
    token = proof(runtime)["probeToken"]
    other = RuntimeStore(runtime.store.path, BASE)
    with other.transition_lock():
        with pytest.raises(ModelRuntimeError) as caught:
            activation(runtime, token)
    assert caught.value.code == "MODEL_TRANSITION_BUSY"
    assert runtime.selection().revision == 0


def test_timeout_and_connection_errors_are_safe_and_do_not_change_state(runtime):
    runtime.settings.ai_model_admin_timeout_seconds = 0.001
    runtime.client.delay = 0.1
    with pytest.raises(ModelRuntimeError) as caught:
        run(runtime.installed())
    assert caught.value.status == 504
    runtime.client.delay = 0
    runtime.client.error = httpx.ConnectError("password=must-not-leak")
    with pytest.raises(ModelRuntimeError) as caught:
        run(runtime.installed())
    assert caught.value.status == 503 and "must-not-leak" not in caught.value.message
    assert runtime.selection().revision == 0


def test_corrupt_state_fails_closed_instead_of_resetting_default(runtime):
    runtime.store.path.write_bytes(b"not-a-database")
    with pytest.raises(ModelRuntimeError) as caught:
        runtime.selection()
    assert caught.value.code == "MODEL_STATE_UNAVAILABLE"


def test_failed_operation_write_rolls_back_selection_in_same_transaction(runtime):
    token = proof(runtime)["probeToken"]
    with runtime.store.connect() as connection:
        connection.execute("CREATE TRIGGER block_operation BEFORE INSERT ON operations BEGIN SELECT RAISE(ABORT,'write failed'); END")
    with pytest.raises(ModelRuntimeError) as caught:
        activation(runtime, token)
    assert caught.value.code == "MODEL_STATE_UNAVAILABLE"
    assert runtime.selection().revision == 0 and runtime.selection().model == BASE


def test_probe_is_invalidated_if_selection_changes_before_evidence_is_saved(runtime):
    original_chat = runtime.client.chat

    async def changed(**body):
        result = await original_chat(**body)
        with runtime.store.connect() as connection:
            connection.execute("UPDATE selection SET revision=revision+1 WHERE id=1")
        return result

    runtime.client.chat = changed
    with pytest.raises(ModelRuntimeError) as caught:
        proof(runtime)
    assert caught.value.code == "MODEL_REVISION_CHANGED"
    with runtime.store.connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM probes").fetchone()[0] == 0


def test_missing_operation_is_explicit_not_success(runtime):
    with pytest.raises(ModelRuntimeError) as caught:
        runtime.operation(uuid4())
    assert caught.value.status == 404


def test_authenticated_routes_and_safe_validation(client, runtime):
    from app.main import app
    app.dependency_overrides[get_model_runtime_service] = lambda: runtime
    headers = {"X-Internal-Api-Key": TEST_API_KEY}
    for method, path, body in [("get", "/installed", None), ("post", "/probe", {}),
        ("post", "/activate", {}), ("post", "/rollback", {}), ("get", f"/operations/{uuid4()}", None)]:
        response = client.request(method, "/api/models" + path, json=body)
        assert response.status_code == 401
    installed = client.get("/api/models/installed", headers=headers)
    assert installed.status_code == 200 and installed.headers["Cache-Control"] == "no-store"
    bad = client.post("/api/models/probe", headers=headers, json={"model": TARGET, "digest": SECOND, "secret": "must-not-leak"})
    assert bad.status_code == 422 and "must-not-leak" not in bad.text
    bad_revision = client.post("/api/models/rollback", headers=headers, json={"operationId": str(uuid4()), "expectedRevision": True})
    assert bad_revision.status_code == 422


def test_route_probe_activate_operation_and_rollback_contract(client, runtime):
    from app.main import app
    app.dependency_overrides[get_model_runtime_service] = lambda: runtime
    headers = {"X-Internal-Api-Key": TEST_API_KEY}
    token = client.post("/api/models/probe", headers=headers, json={"model": TARGET, "digest": SECOND}).json()["probeToken"]
    operation_id = str(uuid4())
    result = client.post("/api/models/activate", headers=headers, json={"operationId": operation_id, "model": TARGET,
        "digest": SECOND, "probeToken": token, "expectedRevision": 0})
    assert result.status_code == 200 and result.json()["status"] == "APPLIED"
    operation = client.get(f"/api/models/operations/{operation_id}", headers=headers)
    assert operation.json() == result.json()
    rollback = client.post("/api/models/rollback", headers=headers, json={"operationId": str(uuid4()), "expectedRevision": 1})
    assert rollback.status_code == 200 and rollback.json()["status"] == "ROLLED_BACK"


def test_analysis_pins_all_stages_and_next_request_uses_new_model():
    from types import SimpleNamespace
    from app.services.llm_service import LlmService
    from tests.test_llm_service import FakeClient, REQUIREMENT_GENERAL_MODEL_JSON, AMBIGUOUS_DB_JSON, db_only, requirement_request, settings
    selection = [Selection(BASE, FIRST, 0)]
    async def validate_selection(_selection):
        return None

    runtime = SimpleNamespace(selection=lambda: selection[0], validate_selection=validate_selection)
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, db_only(AMBIGUOUS_DB_JSON),
                                      REQUIREMENT_GENERAL_MODEL_JSON, db_only(AMBIGUOUS_DB_JSON)])
    service = LlmService(settings(), client=client, runtime=runtime)

    async def changed(code, _):
        if code == "GENERAL_COMPLETED":
            selection[0] = Selection(TARGET, SECOND, 1)

    assert run(service.analyze(requirement_request(), progress=changed)).analysis_model == BASE
    assert [call["model"] for call in client.chat_kwargs] == [BASE, BASE]
    assert run(service.analyze(requirement_request())).analysis_model == TARGET
    assert [call["model"] for call in client.chat_kwargs[2:]] == [TARGET, TARGET]


def test_health_uses_selected_model_not_original_setting(runtime):
    from app.services.llm_service import LlmService
    from tests.test_llm_service import FakeClient
    activation(runtime)
    client = FakeClient(list_response={"models": [{"model": TARGET, "digest": SECOND}]})
    result = run(LlmService(runtime.settings, client=client, runtime=runtime).health())
    assert result.model == TARGET and runtime.settings.ai_model == BASE


def test_health_and_new_analysis_reject_changed_selected_files(runtime):
    from app.services.llm_service import LlmService, ModelUnavailableError
    from tests.test_llm_service import FakeClient, analysis_request
    activation(runtime)
    runtime.client.models[1]["digest"] = THIRD_DIGEST
    client = FakeClient(list_response={"models": [{"model": TARGET, "digest": THIRD_DIGEST}]})
    service = LlmService(runtime.settings, client=client, runtime=runtime)
    with pytest.raises(ModelUnavailableError):
        run(service.health())
    with pytest.raises(ModelUnavailableError):
        run(service.analyze(analysis_request()))
    assert client.chat_calls == 0
