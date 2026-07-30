from tests.conftest import TEST_API_KEY


def test_health_does_not_require_auth_or_call_model(client, fake_llm_service):
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json()["status"] == "UP"
    assert fake_llm_service.health_calls == 0
    assert fake_llm_service.analysis_calls == 0


def test_model_health_requires_internal_api_key(client, fake_llm_service):
    response = client.get("/api/models/health")

    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "INVALID_INTERNAL_API_KEY"
    assert fake_llm_service.health_calls == 0


def test_model_health_returns_configured_model(client, fake_llm_service):
    response = client.get(
        "/api/models/health",
        headers={"X-Internal-Api-Key": TEST_API_KEY},
    )

    assert response.status_code == 200
    assert response.json() == {
        "status": "UP",
        "model": "devstral",
        "available": True,
    }
    assert fake_llm_service.health_calls == 1
