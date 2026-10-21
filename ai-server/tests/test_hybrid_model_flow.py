"""GPU 없이 어댑터의 선택·분석 라우팅·복구를 함께 확인한다."""
import asyncio
from uuid import uuid4
from app.services.hybrid_model_client import HybridModelClient
from app.services.model_runtime import ModelRuntimeService, Selection
from app.services.llm_service import LlmService
from tests.test_model_runtime import FakeAdminClient, FIRST, BASE
from tests.test_llm_service import settings, requirement_request, REQUIREMENT_GENERAL_MODEL_JSON, AMBIGUOUS_DB_JSON, db_only

JOB = "424b376d-2419-4c80-a776-87d7d863b6d2"
ADAPTER = "doenaai-mlx:web-" + JOB
HASH = "b" * 64

class FakeAdapters:
    def __init__(self):
        self.responses, self.calls = [], []
    def owns(self, model):
        return model == ADAPTER
    def source_model(self, model):
        assert model == ADAPTER
        return BASE
    async def list(self):
        return {"models": [{"name": ADAPTER, "digest": HASH, "size": 100}]}
    async def show(self, model):
        assert model == ADAPTER
        return {"capabilities": ["completion"], "sourceModel": BASE, "adapterId": JOB}
    async def chat(self, **body):
        self.calls.append(body)
        return {"model": ADAPTER, "done": True, "done_reason": "stop",
                "message": {"content": self.responses.pop(0) if self.responses else '{"message":"모델 응답 확인"}'}}

def test_prepared_adapter_reuses_probe_activation_and_base_model_rollback(tmp_path):
    async def flow():
        primary, adapters = FakeAdminClient(), FakeAdapters()
        runtime = ModelRuntimeService(settings(ai_model_state_file=str(tmp_path / "selection.db")), client=HybridModelClient(primary, adapters))
        runtime.adapters = adapters
        listing = await runtime.installed()
        target = next(row for row in listing["models"] if row["name"] == ADAPTER)
        assert target["sourceModel"] == BASE and target["provider"] == "mlx" and target["qualityEvaluated"] is False
        proof = await runtime.probe(ADAPTER, HASH)
        result = await runtime.activate(uuid4(), ADAPTER, HASH, proof["probeToken"], 0)
        assert result["status"] == "APPLIED" and result["adapterId"] == JOB
        current = await runtime.installed()
        assert current["sourceModel"] == BASE and current["canRollback"]
        restored = await runtime.rollback(uuid4(), 1)
        assert restored["status"] == "ROLLED_BACK" and restored["model"] == BASE
        assert runtime.selection().digest == FIRST
        assert not any(call[0] == "chat" for call in primary.calls)
    asyncio.run(flow())

def test_analysis_keeps_adapter_for_all_stages_and_records_origin(tmp_path):
    async def flow():
        primary, adapters = FakeAdminClient(), FakeAdapters()
        routed = HybridModelClient(primary, adapters)
        config = settings(ai_model_state_file=str(tmp_path / "selection.db"))
        runtime = ModelRuntimeService(config, client=routed)
        runtime.adapters = adapters
        proof = await runtime.probe(ADAPTER, HASH)
        await runtime.activate(uuid4(), ADAPTER, HASH, proof["probeToken"], 0)
        adapters.calls.clear()
        adapters.responses = [REQUIREMENT_GENERAL_MODEL_JSON, db_only(AMBIGUOUS_DB_JSON)]
        service = LlmService(config, client=routed, runtime=runtime)
        async def change_selection(code, _):
            if code == "GENERAL_COMPLETED":
                await runtime.rollback(uuid4(), 1)
        result = await service.analyze(requirement_request(), progress=change_selection)
        assert len(adapters.calls) == 2 and all(call["model"] == ADAPTER for call in adapters.calls)
        assert result.analysis_model == ADAPTER and result.analysis_source_model == BASE and result.analysis_adapter_digest == HASH
        assert runtime.selection() == Selection(BASE, FIRST, 2)
    asyncio.run(flow())

def test_adapter_import_route_requires_authentication_and_rejects_paths(client):
    from tests.conftest import TEST_API_KEY
    assert client.post("/api/models/adapters/import", json={}).status_code == 401
    body = {"jobId": JOB, "version": "web-" + JOB, "manifestSha256": HASH, "sourceModel": BASE,
            "baseModel": "mlx-community/Qwen3.8-27B-4bit", "baseRevision": "a" * 40, "path": "must-not-be-accepted"}
    reply = client.post("/api/models/adapters/import", json=body, headers={"X-Internal-Api-Key": TEST_API_KEY})
    assert reply.status_code == 422 and "must-not-be-accepted" not in reply.text
