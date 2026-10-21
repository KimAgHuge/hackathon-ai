"""GPU와 실제 추론 엔진 없이 새 모델 등록·출처·응답 유실 복구를 검사한다."""

import asyncio
import json
import shutil
import time
from types import SimpleNamespace
from uuid import uuid4

import httpx
import pytest

from app.config import Settings
from app.dependencies import get_model_runtime_service
from app.services.model_runtime import ModelRuntimeError, ModelRuntimeService
from app.services.trained_model_runtime import OllamaModelImporter, TrainedModelError, TrainedModelRuntime, metadata_input
from tests.conftest import TEST_API_KEY
from tests.test_model_runtime import BASE, FIRST, FakeAdminClient

from trained_model_delivery import create_package

JOB = "424b376d-2419-4c80-a776-87d7d863b6d2"
MODEL = "doenaai-trained:web-" + JOB
ACTUAL = "d" * 64
SECRET = "trained-model-test-signing-key-at-least-32"


class FakeImporter:
    def __init__(self):
        self.calls = []

    def register(self, metadata, weights, deadline, *, check=None):
        assert weights.read_bytes().startswith(b"GGUF") and time.monotonic() < deadline
        self.calls.append(metadata)
        return ACTUAL


@pytest.fixture
def staged(tmp_path):
    shared = tmp_path / "shared"
    source = shared / "model-deliveries" / ("trained-web-" + JOB)
    artifact = tmp_path / "model.gguf"
    artifact.write_bytes(b"GGUF" + b"\0" * 100)
    metadata = create_package(artifact, {"qualityEvaluation": "NOT_EVALUATED", "productionApproved": False},
                              source, JOB, {"modelId": "mlx-community/Qwen3.8-27B-4bit", "revision": "1" * 40}, SECRET)
    settings = Settings(ai_internal_api_key=TEST_API_KEY, training_shared_dir=str(shared), training_transfer_key=SECRET,
                        ai_trained_model_dir=str(tmp_path / "cache"), ai_model_state_file=str(tmp_path / "selection.db"),
                        ai_checkpoints_enabled=False, ai_final_reconciliation_enabled=False)
    return metadata, source, settings


def complete(runtime, metadata):
    async def flow():
        accepted = await runtime.prepare(metadata)
        assert accepted["status"] == "IMPORTING" and "digest" not in accepted
        await runtime.tasks[metadata["jobId"]]
        return runtime.status(JOB)
    return asyncio.run(flow())


def test_registration_verifies_signed_package_and_keeps_actual_engine_digest(staged):
    metadata, _, settings = staged
    importer = FakeImporter()
    runtime = TrainedModelRuntime(settings, importer)
    result = complete(runtime, metadata)
    assert result["status"] == "READY" and result["digest"] == ACTUAL
    assert result["manifestSha256"] != result["digest"] and result["artifactSha256"] != result["digest"]
    assert result["provider"] == "ollama" and result["trainingJobId"] == JOB
    assert result["qualityEvaluated"] is False and len(importer.calls) == 1
    assert not settings.ai_mlx_python and not settings.ai_mlx_model_dir
    repeated = asyncio.run(runtime.prepare(metadata))
    assert repeated == result and len(importer.calls) == 1
    restarted = TrainedModelRuntime(settings, importer)
    assert restarted.metadata_for(MODEL, ACTUAL)["sourceModel"] == BASE
    with pytest.raises(TrainedModelError, match="바뀌"):
        restarted.metadata_for(MODEL, "f" * 64)


def test_missing_cloud_files_wait_without_calling_engine(staged):
    metadata, source, settings = staged
    (source / "model.gguf").unlink()
    importer = FakeImporter()
    assert complete(TrainedModelRuntime(settings, importer), metadata)["status"] == "WAITING"
    assert not importer.calls


def test_changed_signed_file_fails_before_registration(staged):
    metadata, source, settings = staged
    (source / "model.gguf").write_bytes(b"GGUF" + b"changed" * 20)
    importer = FakeImporter()
    result = complete(TrainedModelRuntime(settings, importer), metadata)
    assert result["status"] == "UNAVAILABLE" and result["errorCode"] == "TRAINED_MODEL_IMPORT_FAILED"
    assert not importer.calls and str(source) not in json.dumps(result)


def test_new_model_reserves_space_for_both_cache_and_engine_blob(staged, monkeypatch):
    metadata, source, settings = staged
    importer = FakeImporter()
    free = metadata["artifactSize"] + 128 * 1024 ** 2
    monkeypatch.setattr(shutil, "disk_usage", lambda _: SimpleNamespace(free=free))
    runtime = TrainedModelRuntime(settings, importer)
    result = complete(runtime, metadata)
    assert result["status"] == "UNAVAILABLE" and result["errorCode"] == "TRAINED_MODEL_DISK_FULL"
    assert not importer.calls and (source / "model.gguf").exists()
    assert not (runtime.cache / metadata["version"]).exists()


@pytest.mark.parametrize("enough", [True, False])
def test_cached_model_still_reserves_space_for_engine_registration(staged, monkeypatch, enough):
    metadata, source, settings = staged
    importer = FakeImporter()
    runtime = TrainedModelRuntime(settings, importer)
    runtime._root()
    shutil.copytree(source, runtime.cache / metadata["version"])
    free = metadata["artifactSize"] + 128 * 1024 ** 2 - (0 if enough else 1)
    monkeypatch.setattr(shutil, "disk_usage", lambda _: SimpleNamespace(free=free))
    result = complete(runtime, metadata)
    assert result["status"] == ("READY" if enough else "UNAVAILABLE")
    assert len(importer.calls) == (1 if enough else 0)
    assert (runtime.cache / metadata["version"] / "model.gguf").exists()


@pytest.mark.parametrize("change", [{"artifactSize": True}, {"artifactSize": 65 * 1024 ** 3}, {"path": "/tmp/model.gguf"},
                                    {"model": "qwen3.8:27b"}, {"version": "../other"}, {"baseModel": "other/base"}])
def test_import_metadata_rejects_paths_overwrites_and_invalid_sizes(staged, change):
    metadata, _, _ = staged
    with pytest.raises(TrainedModelError):
        metadata_input({**metadata, **change})


def test_same_job_cannot_be_reused_for_different_artifact(staged):
    metadata, _, settings = staged
    runtime = TrainedModelRuntime(settings, FakeImporter())
    complete(runtime, metadata)
    with pytest.raises(TrainedModelError, match="다른"):
        asyncio.run(runtime.prepare({**metadata, "artifactSha256": "f" * 64}))


def test_interrupted_record_is_visible_and_can_be_retried(staged):
    metadata, _, settings = staged
    runtime = TrainedModelRuntime(settings, FakeImporter())
    runtime._save({"metadata": metadata, "status": "IMPORTING"})
    assert runtime.status(JOB)["errorCode"] == "TRAINED_MODEL_IMPORT_INTERRUPTED"
    assert complete(runtime, metadata)["status"] == "READY"


def test_installed_trained_model_uses_existing_probe_activate_and_rollback(staged):
    metadata, _, settings = staged
    trained = TrainedModelRuntime(settings, FakeImporter())
    complete(trained, metadata)
    engine = FakeAdminClient()
    engine.models.append({"name": MODEL, "digest": ACTUAL, "size": metadata["artifactSize"]})
    engine.capabilities[MODEL] = ["completion"]
    engine.chat_result["model"] = MODEL
    runtime = ModelRuntimeService(settings, client=engine)
    runtime.trained = trained
    async def flow():
        entry = next(row for row in (await runtime.installed())["models"] if row["name"] == MODEL)
        assert entry["sourceModel"] == BASE and entry["trainedModel"] and entry["selectable"]
        proof = await runtime.probe(MODEL, ACTUAL)
        activated = await runtime.activate(uuid4(), MODEL, ACTUAL, proof["probeToken"], 0)
        assert activated["trainingJobId"] == JOB and activated["provider"] == "ollama"
        assert (await runtime.installed())["trainingJobId"] == JOB
        result = await runtime.rollback(uuid4(), 1)
        assert result["model"] == BASE and result["digest"] == FIRST
        engine.models[-1]["digest"] = "f" * 64
        with pytest.raises(ModelRuntimeError, match="바뀌"):
            await runtime.probe(MODEL, "f" * 64)
    asyncio.run(flow())


def test_analysis_pins_trained_model_and_records_original_source_after_rollback(staged):
    from app.services.llm_service import LlmService
    from tests.test_llm_service import requirement_request, REQUIREMENT_GENERAL_MODEL_JSON, AMBIGUOUS_DB_JSON, db_only
    metadata, _, settings = staged
    trained = TrainedModelRuntime(settings, FakeImporter())
    complete(trained, metadata)
    engine = FakeAdminClient()
    engine.models.append({"name": MODEL, "digest": ACTUAL, "size": metadata["artifactSize"]})
    engine.capabilities[MODEL] = ["completion"]
    engine.chat_result["model"] = MODEL
    runtime = ModelRuntimeService(settings, client=engine)
    runtime.trained = trained
    responses, calls = [REQUIREMENT_GENERAL_MODEL_JSON, db_only(AMBIGUOUS_DB_JSON)], []
    class AnalysisClient:
        async def list(self):
            return await engine.list()
        async def chat(self, **body):
            calls.append(body)
            return {"model": MODEL, "done": True, "done_reason": "stop", "message": {"content": responses.pop(0)}}
    async def flow():
        proof = await runtime.probe(MODEL, ACTUAL)
        await runtime.activate(uuid4(), MODEL, ACTUAL, proof["probeToken"], 0)
        service = LlmService(settings, client=AnalysisClient(), runtime=runtime)
        async def change_selection(code, _):
            if code == "GENERAL_COMPLETED":
                await runtime.rollback(uuid4(), 1)
        result = await service.analyze(requirement_request(), progress=change_selection)
        assert len(calls) == 2 and all(call["model"] == MODEL for call in calls)
        assert result.analysis_model == MODEL and result.analysis_source_model == BASE
        assert result.analysis_trained_model_digest == ACTUAL and result.analysis_adapter_digest is None
        assert result.model_dump(by_alias=True)["analysisTrainedModelDigest"] == ACTUAL
        assert runtime.selection().model == BASE
    asyncio.run(flow())


def test_engine_uploads_file_blob_and_creates_full_model_without_from(staged, monkeypatch):
    metadata, source, settings = staged
    calls, created = [], False
    template = "{{ .Prompt }}"
    def handle(request):
        nonlocal created
        calls.append(request)
        path = request.url.path
        if path == "/api/tags":
            return httpx.Response(200, json={"models": [{"name": MODEL, "digest": ACTUAL}] if created else []})
        if path == "/api/show":
            return httpx.Response(200, json={"capabilities": ["completion"], "template": template, "parser": "qwen3.5", "renderer": "qwen3.5",
                                            "model_info": {"general.architecture": "qwen35"}, "details": {"quantization_level": "Q4_K_M"}})
        if path.startswith("/api/blobs/"):
            if request.method == "HEAD":
                return httpx.Response(404)
            assert request.read() == (source / "model.gguf").read_bytes()
            return httpx.Response(201)
        if path == "/api/create":
            body = json.loads(request.read())
            assert "from" not in body and "adapters" not in body and body["stream"] is False
            assert body["files"] == {"model.gguf": "sha256:" + metadata["artifactSha256"]}
            assert body["template"] == template and body["parser"] == "qwen3.5"
            created = True
            return httpx.Response(200, json={"status": "success"})
        raise AssertionError(path)
    factory = httpx.Client
    monkeypatch.setattr(httpx, "Client", lambda **kwargs: factory(**kwargs, transport=httpx.MockTransport(handle)))
    assert OllamaModelImporter(settings).register(metadata, source / "model.gguf", time.monotonic() + 60) == ACTUAL
    assert not any(request.url.path == "/api/chat" for request in calls)


@pytest.mark.parametrize("same_file", [True, False])
def test_lost_create_response_recovers_only_identical_model(staged, monkeypatch, same_file):
    metadata, source, settings = staged
    template = "{{ .Prompt }}"
    def handle(request):
        if request.url.path == "/api/tags":
            return httpx.Response(200, json={"models": [{"name": MODEL, "digest": ACTUAL}]})
        assert request.url.path == "/api/show"
        blob = metadata["artifactSha256"] if same_file else "f" * 64
        return httpx.Response(200, json={"capabilities": ["completion"], "template": template,
                                       "model_info": {"general.architecture": "qwen35"},
                                       "modelfile": "FROM /engine/blobs/sha256-" + blob})
    factory = httpx.Client
    monkeypatch.setattr(httpx, "Client", lambda **kwargs: factory(**kwargs, transport=httpx.MockTransport(handle)))
    importer = OllamaModelImporter(settings)
    if same_file:
        assert importer.register(metadata, source / "model.gguf", time.monotonic() + 60) == ACTUAL
    else:
        with pytest.raises(TrainedModelError, match="다른"):
            importer.register(metadata, source / "model.gguf", time.monotonic() + 60)


def test_import_routes_require_authentication_and_never_accept_paths(client, staged):
    from app.main import app
    metadata, _, settings = staged
    runtime = ModelRuntimeService(settings, client=FakeAdminClient())
    app.dependency_overrides[get_model_runtime_service] = lambda: runtime
    headers = {"X-Internal-Api-Key": TEST_API_KEY}
    assert client.post("/api/models/trained/import", json=metadata).status_code == 401
    assert client.get("/api/models/trained/imports/" + JOB).status_code == 401
    status = client.get("/api/models/trained/imports/" + JOB, headers=headers)
    assert status.status_code == 200 and status.json()["status"] == "NOT_STARTED"
    assert status.headers["Cache-Control"] == "no-store"
    result = client.post("/api/models/trained/import", json={**metadata, "path": "/tmp/private-model"}, headers=headers)
    assert result.status_code == 422 and "/tmp/private-model" not in result.text
