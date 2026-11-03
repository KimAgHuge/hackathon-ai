"""GPU와 실제 추론 엔진 없이 새 모델 등록·출처·응답 유실 복구를 검사한다."""

import asyncio
import json
import shutil
import threading
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


def test_cloud_download_is_visible_and_finishes_before_model_registration(staged, monkeypatch):
    from app.services import trained_model_runtime as module
    metadata, _, settings = staged
    importer = FakeImporter()
    runtime = TrainedModelRuntime(settings, importer)
    downloading, downloaded = threading.Event(), threading.Event()
    phases = []
    save = runtime._save

    def record(state):
        if state.get("phase"):
            phases.append(state["phase"])
        save(state)

    def hydrate(package, filenames, deadline, *, check, progress):
        progress("DOWNLOADING")
        downloading.set()
        assert downloaded.wait(timeout=3)
        check()

    monkeypatch.setattr(module, "ensure_local_files", hydrate)
    monkeypatch.setattr(runtime, "_save", record)

    async def flow():
        await runtime.prepare(metadata)
        try:
            assert await asyncio.to_thread(downloading.wait, 3)
            state = runtime.status(JOB)
            assert state["status"] == "IMPORTING" and state["phase"] == "DOWNLOADING"
            assert "다운로드를 요청했습니다" in state["message"] and "기다립니다" in state["message"]
            assert not importer.calls and not (runtime.cache / metadata["version"]).exists()
        finally:
            downloaded.set()
            await runtime.tasks[JOB]
        result = runtime.status(JOB)
        assert result["status"] == "READY" and "phase" not in result
        assert len(importer.calls) == 1
        assert phases == ["DOWNLOADING", "VERIFYING", "COPYING", "REGISTERING"]

    asyncio.run(flow())


@pytest.mark.parametrize("failure", ["timeout", "unavailable"])
def test_cloud_download_failure_never_reads_or_registers_the_model(staged, monkeypatch, failure):
    from app.services import trained_model_runtime as module
    from app.services.cloud_model_files import CloudModelFilesError
    metadata, _, settings = staged
    importer = FakeImporter()
    runtime = TrainedModelRuntime(settings, importer)

    def hydrate(*args, progress, **kwargs):
        progress("DOWNLOADING")
        if failure == "timeout":
            raise TimeoutError()
        raise CloudModelFilesError("TRAINED_MODEL_CLOUD_DOWNLOAD_UNAVAILABLE", "iCloud 연결과 접근 권한을 확인하세요.")

    def reject_read(*args, **kwargs):
        pytest.fail("다운로드 전에 모델 본문을 검사했습니다.")

    monkeypatch.setattr(module, "ensure_local_files", hydrate)
    monkeypatch.setattr("trained_model_delivery.verify_package", reject_read)
    result = complete(runtime, metadata)
    assert result["status"] == "UNAVAILABLE" and "phase" not in result
    expected = "TRAINED_MODEL_IMPORT_TIMEOUT" if failure == "timeout" else "TRAINED_MODEL_CLOUD_DOWNLOAD_UNAVAILABLE"
    assert result["errorCode"] == expected and not importer.calls
    assert not (runtime.cache / metadata["version"]).exists()


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
    from tests.test_llm_service import requirement_request, REQUIREMENT_GENERAL_MODEL_JSON, AMBIGUOUS_DB_JSON, db_only, _enrich_internal_fixture
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
            return {"model": MODEL, "done": True, "done_reason": "stop", "message": {"content": _enrich_internal_fixture(responses.pop(0), body)}}
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


def test_engine_restores_native_format_and_generation_defaults_from_show(staged, monkeypatch):
    metadata, source, settings = staged
    created = None
    show = {"capabilities": ["completion"], "template": "{{ .Prompt }}",
            "model_info": {"general.architecture": "qwen35"}, "details": {"quantization_level": "Q4_K_M"},
            "parameters": "temperature 1\nrepeat_penalty 1\ntop_k 20\ntop_p 0.95\nmin_p 0\n"
                          "stop \"<|im_end|>\"\nstop \"<|endoftext|>\"\nnum_ctx 32768\nnum_predict 4096\nseed 42\nrepeat_last_n 64\n"
                          "presence_penalty 0\ndraft_num_predict 4\nnum_gpu 20\nfuture_engine_option 1",
            "modelfile": 'FROM /engine/base\nTEMPLATE """{{ .Prompt }}\nPARSER fake-in-template\n"""\n'
                         'SYSTEM """\nRENDERER fake-in-system\n"""\n'
                         'MESSAGE user """\nPARSER fake-in-message\n"""\nRENDERER qwen3.8\nPARSER qwen3.5'}

    def handle(request):
        nonlocal created
        if request.url.path == "/api/tags":
            return httpx.Response(200, json={"models": [{"name": MODEL, "digest": ACTUAL}] if created else []})
        if request.url.path == "/api/show":
            installed = json.loads(request.read())["model"] == MODEL
            result = dict(show)
            if installed:
                result["parameters"] = created["parameters"]
            return httpx.Response(200, json=result)
        if request.url.path.startswith("/api/blobs/"):
            assert request.method == "HEAD"
            return httpx.Response(200)
        assert request.url.path == "/api/create"
        created = json.loads(request.read())
        assert created["parser"] == "qwen3.5" and created["renderer"] == "qwen3.8"
        assert created["template"] == show["template"]
        assert created["parameters"] == {"temperature": 1, "repeat_penalty": 1, "top_k": 20, "top_p": 0.95,
            "min_p": 0, "stop": ["<|im_end|>", "<|endoftext|>"], "num_ctx": 32768, "num_predict": 4096,
            "seed": 42, "repeat_last_n": 64, "presence_penalty": 0}
        assert not {"from", "system", "modelfile"} & created.keys()
        return httpx.Response(200, json={"status": "success"})

    factory = httpx.Client
    monkeypatch.setattr(httpx, "Client", lambda **kwargs: factory(**kwargs, transport=httpx.MockTransport(handle)))
    assert OllamaModelImporter(settings).register(metadata, source / "model.gguf", time.monotonic() + 60) == ACTUAL


@pytest.mark.parametrize("change", ["missing_renderer", "changed_parser", "changed_parameters"])
@pytest.mark.parametrize("existing", [False, True])
def test_engine_rejects_same_weights_with_different_generation_settings(staged, monkeypatch, change, existing):
    metadata, source, settings = staged
    created = existing
    show = {"capabilities": ["completion"], "template": "{{ .Prompt }}",
            "model_info": {"general.architecture": "qwen35"}, "details": {"quantization_level": "Q4_K_M"},
            "parameters": "temperature 1\nmin_p 0", "modelfile": "RENDERER qwen3.8\nPARSER qwen3.5"}

    def handle(request):
        nonlocal created
        if request.url.path == "/api/tags":
            return httpx.Response(200, json={"models": [{"name": MODEL, "digest": ACTUAL}] if created else []})
        if request.url.path == "/api/show":
            result = dict(show)
            if json.loads(request.read())["model"] == MODEL:
                result["modelfile"] = "FROM /engine/blobs/sha256-" + metadata["artifactSha256"] + "\n" + show["modelfile"]
                if change == "missing_renderer":
                    result["modelfile"] = result["modelfile"].replace("RENDERER qwen3.8\n", "")
                elif change == "changed_parser":
                    result["modelfile"] = result["modelfile"].replace("PARSER qwen3.5", "PARSER other")
                else:
                    result["parameters"] = "temperature 0\nmin_p 0"
            return httpx.Response(200, json=result)
        assert not existing, "기존 모델에는 파일 전달이나 생성을 요청하면 안 됩니다."
        if request.url.path.startswith("/api/blobs/"):
            return httpx.Response(200)
        assert request.url.path == "/api/create"
        created = True
        return httpx.Response(200, json={"status": "success"})

    factory = httpx.Client
    monkeypatch.setattr(httpx, "Client", lambda **kwargs: factory(**kwargs, transport=httpx.MockTransport(handle)))
    importer = OllamaModelImporter(settings)
    if existing:
        with pytest.raises(TrainedModelError) as error:
            importer.register(metadata, source / "model.gguf", time.monotonic() + 60)
        assert error.value.code == "TRAINED_MODEL_NAME_CONFLICT"
    else:
        with pytest.raises(ValueError):
            importer.register(metadata, source / "model.gguf", time.monotonic() + 60)


@pytest.mark.parametrize("settings", [
    {"modelfile": "RENDERER qwen3.8\nRENDERER qwen3.8"},
    {"modelfile": "PARSER qwen3.5", "parser": "other"},
    {"modelfile": "RENDERER qwen3.8; FROM other"},
    {"modelfile": "PARSER ../other"},
    {"modelfile": 'TEMPLATE """\nRENDERER qwen3.8'},
    {"parameters": "temperature 1\ntemperature 0"},
    {"parameters": "temperature NaN"},
    {"parameters": "top_p 1.5"},
    {"parameters": "top_k 1.5"},
    {"parameters": {"seed": True}},
    {"parameters": {"min_p": float("inf")}},
    {"parameters": "stop FROM other"},
])
def test_engine_rejects_ambiguous_or_invalid_source_settings_before_upload(staged, monkeypatch, settings):
    metadata, source, configured = staged

    def handle(request):
        if request.url.path == "/api/tags":
            return httpx.Response(200, json={"models": []})
        assert request.url.path == "/api/show"
        return httpx.Response(200, content=json.dumps({"capabilities": ["completion"], "template": "{{ .Prompt }}",
            "model_info": {"general.architecture": "qwen35"}, **settings}).encode(), headers={"content-type": "application/json"})

    factory = httpx.Client
    monkeypatch.setattr(httpx, "Client", lambda **kwargs: factory(**kwargs, transport=httpx.MockTransport(handle)))
    with pytest.raises(TrainedModelError) as error:
        OllamaModelImporter(configured).register(metadata, source / "model.gguf", time.monotonic() + 60)
    assert error.value.code == "TRAINED_MODEL_TEMPLATE_INVALID"


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
