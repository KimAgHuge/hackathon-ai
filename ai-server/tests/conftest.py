import pytest
from fastapi.testclient import TestClient

from app.config import Settings, get_settings
from app.dependencies import get_llm_service
from app.main import app
from app.models.analysis import ModelHealthResponse, ProjectAnalysisResponse

TEST_API_KEY = "test-internal-api-key-at-least-32-characters"


class FakeLlmService:
    def __init__(self):
        self.analysis_calls = 0
        self.health_calls = 0
        self.analysis_exception = None
        self.health_exception = None

    async def health(self):
        self.health_calls += 1
        if self.health_exception:
            raise self.health_exception
        return ModelHealthResponse(
            status="UP",
            model="devstral",
            available=True,
        )

    async def analyze(self, request):
        self.analysis_calls += 1
        if self.analysis_exception:
            raise self.analysis_exception
        return ProjectAnalysisResponse(
            summary="Vue와 Spring Boot로 구성된 프로젝트입니다.",
            plain_language_summary="시스템의 현재 구성을 살펴본 결과이며, 아직 변경 작업을 확정한 것은 아닙니다.",
            frameworks=["Vue", "Spring Boot", "Oracle"],
            risk_level="UNKNOWN",
            risk_score=0,
            impact_areas=[],
            evidences=[],
            recommendations=["정적 분석 근거를 추가하세요."],
            database_impact={
                "status": "NOT_APPLICABLE",
                "explanation": "변경 요구사항이 없는 프로젝트 구조 분석입니다.",
                "observedTables": [],
                "proposedChanges": [],
                "conditionalPaths": [],
                "openQuestions": [],
            },
        )


@pytest.fixture
def fake_llm_service():
    return FakeLlmService()


@pytest.fixture
def client(fake_llm_service):
    settings = Settings(ai_internal_api_key=TEST_API_KEY)
    app.dependency_overrides[get_settings] = lambda: settings
    app.dependency_overrides[get_llm_service] = lambda: fake_llm_service
    with TestClient(app) as test_client:
        yield test_client
    app.dependency_overrides.clear()
