"""내부 등록 경로가 새 모델의 전체 설정을 보존하고 불완전한 요청을 거부하는지 확인한다."""

from types import SimpleNamespace

from app.dependencies import get_model_runtime_service
from app.main import app
from app.services.trained_model_runtime import metadata_input
from tests.conftest import TEST_API_KEY
from training_profiles import QWEN_PROFILE


def test_import_route_preserves_format_two_and_rejects_incomplete_provenance(client):
    job = "424b376d-2419-4c80-a776-87d7d863b6d2"
    profile = {**QWEN_PROFILE, "modelType": "exaone4", "exportArchitecture": "exaone4",
               "exporter": "mlx-lm-hf-gguf-v1", "loraKeys": ["self_attn.q_proj"]}
    metadata = {"jobId": job, "version": "trained-web-" + job, "manifestSha256": "a" * 64,
                "sourceModel": "exaone4:test", "baseModel": "synthetic/exaone4", "baseRevision": "b" * 40,
                "model": "doenaai-trained:web-" + job, "artifactSha256": "c" * 64, "artifactSize": 256,
                "quantization": "Q4_K_M", "formatVersion": 2, "modelType": "exaone4",
                "architecture": "exaone4", "profile": profile}
    received = []

    class Importer:
        async def prepare(self, value):
            received.append(metadata_input(value))
            return {"status": "IMPORTING", "jobId": value["jobId"]}

    app.dependency_overrides[get_model_runtime_service] = lambda: SimpleNamespace(trained=Importer())
    headers = {"X-Internal-Api-Key": TEST_API_KEY}
    result = client.post("/api/models/trained/import", json=metadata, headers=headers)
    assert result.status_code == 200 and result.json()["status"] == "IMPORTING"
    assert result.headers["Cache-Control"] == "no-store" and received == [metadata]
    incomplete = {key: value for key, value in metadata.items() if key != "profile"}
    assert client.post("/api/models/trained/import", json=incomplete, headers=headers).status_code == 422
    assert client.post("/api/models/trained/import", json={**metadata, "formatVersion": True}, headers=headers).status_code == 422
    assert client.post("/api/models/trained/import", json={**metadata, "path": "/private/model"}, headers=headers).status_code == 422
