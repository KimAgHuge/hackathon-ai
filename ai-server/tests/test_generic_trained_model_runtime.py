"""실제 엔진 없이 공통 학습 모델의 서명 자료와 모델별 등록 검사를 확인한다."""

import asyncio
import json
import struct
import time

import httpx
import pytest

from app.config import Settings
from app.services.trained_model_runtime import OllamaModelImporter, TrainedModelError, TrainedModelRuntime, metadata_input
from tests.conftest import TEST_API_KEY
from tests.test_trained_model_runtime import ACTUAL, JOB, MODEL, SECRET, FakeImporter
from trained_model_delivery import create_package
from training_profiles import QWEN_PROFILE


def gguf(path, architecture):
    def string(value):
        encoded = value.encode()
        return struct.pack("<Q", len(encoded)) + encoded
    fields = [("general.architecture", architecture), ("general.file_type", 15), ("tokenizer.chat_template", "{{ messages }}")]
    value = b"GGUF" + struct.pack("<IQQ", 3, 1, len(fields))
    for key, item in fields:
        value += string(key) + (struct.pack("<I", 8) + string(item) if isinstance(item, str) else struct.pack("<II", 4, item))
    value += string("token_embd.weight") + struct.pack("<IQIQ", 1, 256, 12, 0)
    value += b"\0" * (-len(value) % 32)
    path.write_bytes(value + b"\0" * 144)


@pytest.fixture(params=[("exaone4", "exaone4"), ("qwen3", "qwen3"), ("mistral", "llama")])
def generic_staged(tmp_path, request):
    model_type, architecture = request.param
    profile = {**QWEN_PROFILE, "modelType": model_type, "loraKeys": ["self_attn.q_proj"],
               "exporter": "mlx-lm-hf-gguf-v1", "exportArchitecture": architecture}
    artifact = tmp_path / "model.gguf"
    gguf(artifact, architecture)
    source = tmp_path / "shared/model-deliveries" / ("trained-web-" + JOB)
    metadata = create_package(artifact, {"qualityEvaluation": "NOT_EVALUATED", "productionApproved": False},
        source, JOB, {"modelId": "synthetic/" + model_type + "-4bit", "revision": "6" * 40}, SECRET,
        source_model=model_type + ":test", profile=profile)
    settings = Settings(ai_internal_api_key=TEST_API_KEY, training_shared_dir=str(tmp_path / "shared"), training_transfer_key=SECRET,
                        ai_trained_model_dir=str(tmp_path / "cache"), ai_model_state_file=str(tmp_path / "selection.db"))
    return metadata, source, settings


def test_format_two_registration_preserves_original_source_and_profile(generic_staged):
    metadata, _, settings = generic_staged
    importer = FakeImporter()
    runtime = TrainedModelRuntime(settings, importer)
    async def flow():
        accepted = await runtime.prepare(metadata)
        assert accepted["status"] == "IMPORTING"
        await runtime.tasks[JOB]
        return runtime.status(JOB)
    result = asyncio.run(flow())
    assert result["status"] == "READY" and result["profile"] == metadata["profile"]
    assert result["architecture"] == metadata["architecture"] and result["qualityEvaluated"] is False
    assert runtime.metadata_for(MODEL, ACTUAL)["sourceModel"] == metadata["sourceModel"]


def test_format_two_metadata_requires_matching_model_profile_and_strict_fields(generic_staged):
    metadata, _, _ = generic_staged
    assert metadata_input(metadata) == metadata
    for changed in ({"architecture": "qwen35"}, {"modelType": "unsupported"}, {"formatVersion": 1},
                    {"profile": {**metadata["profile"], "path": "/private/model"}}, {"artifactSize": True}):
        with pytest.raises(TrainedModelError):
            metadata_input({**metadata, **changed})
    missing = dict(metadata)
    missing.pop("profile")
    with pytest.raises(TrainedModelError):
        metadata_input(missing)


@pytest.mark.parametrize("matching_architecture", [True, False])
def test_engine_uses_selected_family_and_rejects_source_architecture_mismatch(generic_staged, monkeypatch, matching_architecture):
    metadata, source, settings = generic_staged
    created, uploads, templates = False, [], []
    template = "{{ .Prompt }}"
    def handle(request):
        nonlocal created
        path = request.url.path
        if path == "/api/tags":
            return httpx.Response(200, json={"models": [{"name": MODEL, "digest": ACTUAL}] if created else []})
        if path == "/api/show":
            templates.append(json.loads(request.read())["model"])
            return httpx.Response(200, json={"capabilities": ["completion"], "template": template,
                "model_info": {"general.architecture": metadata["architecture"] if matching_architecture else "qwen35"},
                "details": {"quantization_level": "Q4_K_M"}})
        if path.startswith("/api/blobs/"):
            uploads.append(request.method)
            if request.method == "HEAD":
                return httpx.Response(404)
            assert request.read() == (source / "model.gguf").read_bytes()
            return httpx.Response(201)
        if path == "/api/create":
            body = json.loads(request.read())
            assert body["model"] == MODEL and body["template"] == template
            assert "from" not in body and "adapters" not in body
            created = True
            return httpx.Response(200, json={"status": "success"})
        raise AssertionError(path)
    factory = httpx.Client
    monkeypatch.setattr(httpx, "Client", lambda **kwargs: factory(**kwargs, transport=httpx.MockTransport(handle)))
    importer = OllamaModelImporter(settings)
    if matching_architecture:
        assert importer.register(metadata, source / "model.gguf", time.monotonic() + 60) == ACTUAL
        assert templates == [metadata["sourceModel"], MODEL]
    else:
        with pytest.raises(TrainedModelError, match="기반"):
            importer.register(metadata, source / "model.gguf", time.monotonic() + 60)
        assert not uploads and not created
