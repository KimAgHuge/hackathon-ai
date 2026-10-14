import asyncio
import json
from types import SimpleNamespace

import httpx
import pytest
from pydantic import ValidationError

from app.config import Settings
from app.models.analysis import DatabaseImpact, ProjectAnalysisRequest
from app.services.llm_service import (
    InvalidModelResponseError,
    LlmService,
    ModelTimeoutError,
    ModelUnavailableError,
)


def settings(**overrides):
    values = {
        "ai_internal_api_key": "test-internal-api-key-at-least-32-characters",
        "ai_max_validation_retries": 1,
    }
    values.update(overrides)
    return Settings(**values)


def analysis_request():
    return ProjectAnalysisRequest.model_validate(
        {
            "projectId": "00000000-0000-0000-0000-000000000000",
            "sourceSummary": {"frameworks": ["Vue", "Spring Boot"]},
            "databaseSummary": {"type": "ORACLE"},
            "relations": [],
        }
    )


def requirement_request():
    return ProjectAnalysisRequest.model_validate(
        {
            "projectId": "00000000-0000-0000-0000-000000000000",
            "sourceSummary": {
                "changeRequirement": {
                    "title": "한 팀만 프로젝트를 관리",
                    "refinedContent": "프로젝트 관리를 하나의 팀으로 제한한다.",
                }
            },
            "databaseSummary": {
                "type": "POSTGRESQL",
                "tables": [
                    {"schema": "public", "name": "projects", "columns": [{"name": "organization_id"}]},
                    {"schema": "public", "name": "project_members", "columns": [{"name": "user_id"}]},
                ],
            },
            "relations": [],
        }
    )


def presentation_requirement_request():
    payload = requirement_request().model_dump(mode="json", by_alias=True)
    payload["sourceSummary"]["changeRequirement"] = {
        "title": "버튼 색상 변경",
        "refinedContent": "저장 동작은 그대로 두고 버튼의 색상과 간격만 바꾼다.",
    }
    return ProjectAnalysisRequest.model_validate(payload)


class FakeClient:
    def __init__(self, chat_responses=None, list_response=None, exception=None):
        self.chat_responses = list(chat_responses or [])
        self.list_response = list_response
        self.exception = exception
        self.chat_calls = 0
        self.chat_kwargs = []

    async def chat(self, **kwargs):
        self.chat_calls += 1
        self.chat_kwargs.append(kwargs)
        if self.exception:
            raise self.exception
        return SimpleNamespace(
            message=SimpleNamespace(content=self.chat_responses.pop(0))
        )

    async def list(self):
        if self.exception:
            raise self.exception
        return self.list_response


VALID_MODEL_JSON = """{
  "summary": "프로젝트 구조 요약",
  "frameworks": ["Vue", "Spring Boot", "Oracle"],
  "riskLevel": "UNKNOWN",
  "riskScore": 0,
  "impactAreas": [],
  "evidences": [],
  "recommendations": [],
  "databaseImpact": {
    "status": "NOT_APPLICABLE",
    "explanation": "변경 요구사항이 없는 구조 분석입니다.",
    "observedTables": [],
    "proposedChanges": [],
    "conditionalPaths": [],
    "openQuestions": []
  }
}"""


AMBIGUOUS_DB_JSON = """{
  "summary": "팀의 의미에 따라 DB 변경 범위가 달라집니다.",
  "frameworks": [],
  "riskLevel": "UNKNOWN",
  "riskScore": 0,
  "impactAreas": ["프로젝트 소유권"],
  "evidences": [],
  "recommendations": ["팀과 조직의 관계를 먼저 확정하세요."],
  "databaseImpact": {
    "status": "NEEDS_CLARIFICATION",
    "explanation": "public.projects.organization_id는 관찰됐지만 팀이 조직과 같은지는 알 수 없습니다.",
    "observedTables": ["public.projects", "public.project_members"],
    "proposedChanges": [],
    "conditionalPaths": [
      {"condition": "팀이 기존 조직과 같은 개념", "databaseEffect": "public.projects.organization_id를 그대로 사용하므로 소유권 컬럼 추가는 필요하지 않을 수 있습니다."},
      {"condition": "팀이 조직과 별도 개념", "databaseEffect": "별도 팀 식별자와 public.projects 연결, 기존 데이터 이관이 필요할 수 있습니다."}
    ],
    "openQuestions": ["팀은 기존 조직과 같은 개념인가요?"]
  }
}"""


CONCRETE_DB_JSON = """{
  "summary": "별도 팀 소유권이 필요합니다.",
  "frameworks": [],
  "riskLevel": "MEDIUM",
  "riskScore": 50,
  "impactAreas": ["프로젝트 소유권"],
  "evidences": [],
  "recommendations": ["기존 프로젝트의 소유 팀을 이관하세요."],
  "databaseImpact": {
    "status": "CHANGE_REQUIRED",
    "explanation": "현재 projects에는 별도 팀 연결이 없습니다.",
    "observedTables": ["public.projects"],
    "proposedChanges": [{
      "targetTable": "public.projects",
      "targetColumn": "team_id",
      "changeType": "ADD_COLUMN",
      "description": "팀 식별자 컬럼을 추가합니다.",
      "rationale": "프로젝트마다 관리 팀을 명시하기 위해서입니다."
    }],
    "conditionalPaths": [],
    "openQuestions": []
  }
}"""

GENERAL_MODEL_JSON = json.dumps({
    key: value for key, value in json.loads(VALID_MODEL_JSON).items() if key != "databaseImpact"
}, ensure_ascii=False)
REQUIREMENT_GENERAL_MODEL_JSON = json.dumps({
    **json.loads(GENERAL_MODEL_JSON),
    "databaseRelevance": "RELATED",
    "databaseRelevanceReason": "팀 소유권 변경은 저장된 프로젝트 관계에 영향을 줄 수 있습니다.",
}, ensure_ascii=False)
UNRELATED_GENERAL_MODEL_JSON = json.dumps({
    **json.loads(GENERAL_MODEL_JSON),
    "databaseRelevance": "UNRELATED",
    "databaseRelevanceReason": "버튼의 색상과 간격만 바꾸며 저장 동작은 유지하므로 DB 영향이 없습니다.",
}, ensure_ascii=False)


def db_only(full_response):
    return json.dumps(json.loads(full_response)["databaseImpact"], ensure_ascii=False)


def test_analyze_validates_structured_response():
    client = FakeClient(chat_responses=[GENERAL_MODEL_JSON])
    service = LlmService(settings(), client=client)

    result = asyncio.run(service.analyze(analysis_request()))

    assert result.risk_level == "UNKNOWN"
    assert result.risk_score == 0
    assert client.chat_calls == 1


def test_requirement_analysis_requires_grounded_db_decision():
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, db_only(AMBIGUOUS_DB_JSON)])
    result = asyncio.run(LlmService(settings(), client=client).analyze(requirement_request()))

    assert result.database_impact.status.value == "NEEDS_CLARIFICATION"
    assert result.database_impact.observed_tables == ["public.projects", "public.project_members"]
    assert client.chat_calls == 2


def test_explicit_business_term_mapping_reaches_both_analysis_steps():
    request = requirement_request()
    clarification = "여기서 팀은 기존 소속 조직(organization)을 의미한다."
    request.source_summary["changeRequirement"]["refinedContent"] += " " + clarification
    request.database_summary["tables"][0]["foreignKeys"] = [{
        "column": "organization_id", "referencedSchema": "public",
        "referencedTable": "organizations", "referencedColumn": "id",
    }]
    request.database_summary["tables"].append({
        "schema": "public", "name": "organizations", "columns": [{"name": "id"}],
    })
    no_schema_change = {
        "status": "NO_CHANGE",
        "explanation": "팀은 기존 조직이며 public.projects.organization_id가 이미 프로젝트당 한 조직을 참조합니다.",
        "observedTables": ["public.projects"],
        "proposedChanges": [],
        "conditionalPaths": [],
        "openQuestions": ["기존 프로젝트에 조직이 비어 있는 데이터가 있는지 확인해야 합니다."],
    }
    client = FakeClient(chat_responses=[
        REQUIREMENT_GENERAL_MODEL_JSON,
        json.dumps(no_schema_change, ensure_ascii=False),
    ])

    result = asyncio.run(LlmService(settings(), client=client).analyze(request))

    assert result.database_impact.status.value == "NO_CHANGE"
    assert client.chat_calls == 2
    assert all(clarification in call["messages"][1]["content"] for call in client.chat_kwargs)


def test_database_prompt_handles_an_unrelated_repository_domain():
    payload = requirement_request().model_dump(mode="json", by_alias=True)
    payload["sourceSummary"]["changeRequirement"] = {
        "title": "창고별 재고 품목 관리",
        "refinedContent": "각 재고 품목은 기존 창고 하나에 소속된다.",
    }
    payload["databaseSummary"]["tables"] = [
        {"schema": "inventory", "name": "items", "columns": [{"name": "warehouse_id"}],
         "foreignKeys": [{"column": "warehouse_id", "referencedSchema": "inventory",
                          "referencedTable": "warehouses", "referencedColumn": "id"}]},
        {"schema": "inventory", "name": "warehouses", "columns": [{"name": "id"}]},
    ]
    request = ProjectAnalysisRequest.model_validate(payload)
    db_result = {
        "status": "NO_CHANGE",
        "explanation": "재고 품목의 창고 연결은 inventory.items.warehouse_id로 표현됩니다.",
        "observedTables": ["inventory.items", "inventory.warehouses"],
        "proposedChanges": [],
        "conditionalPaths": [],
        "openQuestions": [],
    }
    client = FakeClient(chat_responses=[
        REQUIREMENT_GENERAL_MODEL_JSON,
        json.dumps(db_result, ensure_ascii=False),
    ])

    result = asyncio.run(LlmService(settings(), client=client).analyze(request))

    assert result.database_impact.status.value == "NO_CHANGE"
    assert "창고별 재고 품목 관리" in client.chat_kwargs[1]["messages"][1]["content"]
    db_system_prompt = client.chat_kwargs[1]["messages"][0]["content"]
    assert "single-valued foreign key" in db_system_prompt
    assert "team and an organization" not in db_system_prompt
    assert "project row" not in db_system_prompt


def test_presentation_only_requirement_skips_database_model_call():
    client = FakeClient(chat_responses=[UNRELATED_GENERAL_MODEL_JSON])

    result = asyncio.run(LlmService(settings(), client=client).analyze(presentation_requirement_request()))

    assert result.database_impact.status.value == "NOT_APPLICABLE"
    assert "버튼" in result.database_impact.explanation
    assert client.chat_calls == 1


def test_uncertain_database_relevance_still_calls_database_model():
    uncertain = REQUIREMENT_GENERAL_MODEL_JSON.replace('"RELATED"', '"UNCERTAIN"')
    client = FakeClient(chat_responses=[uncertain, db_only(AMBIGUOUS_DB_JSON)])

    result = asyncio.run(LlmService(settings(), client=client).analyze(requirement_request()))

    assert result.database_impact.status.value == "NEEDS_CLARIFICATION"
    assert client.chat_calls == 2


def test_explicit_db_requirement_cannot_be_classified_unrelated():
    unrelated = UNRELATED_GENERAL_MODEL_JSON
    client = FakeClient(chat_responses=[unrelated, REQUIREMENT_GENERAL_MODEL_JSON, db_only(AMBIGUOUS_DB_JSON)])
    request = requirement_request()
    request.source_summary["changeRequirement"]["refinedContent"] += " DB 스키마 변경도 확인한다."

    result = asyncio.run(LlmService(settings(), client=client).analyze(request))

    assert result.database_impact.status.value == "NEEDS_CLARIFICATION"
    assert client.chat_calls == 3


def test_requirement_analysis_retries_generic_response_without_db_impact():
    missing_db_impact = db_only(AMBIGUOUS_DB_JSON).replace('"status":', '"missingStatus":')
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, missing_db_impact, db_only(AMBIGUOUS_DB_JSON)])

    result = asyncio.run(LlmService(settings(), client=client).analyze(requirement_request()))

    assert result.database_impact.status.value == "NEEDS_CLARIFICATION"
    assert client.chat_calls == 3
    assert "status" in client.chat_kwargs[2]["messages"][-1]["content"]


def test_requirement_analysis_rejects_unobserved_table_even_when_json_is_valid():
    invented_table = db_only(AMBIGUOUS_DB_JSON).replace("public.project_members", "public.teams")
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, invented_table, invented_table])

    with pytest.raises(InvalidModelResponseError):
        asyncio.run(LlmService(settings(), client=client).analyze(requirement_request()))


def test_requirement_analysis_retries_when_business_entity_is_ignored():
    off_topic = db_only(AMBIGUOUS_DB_JSON).replace("팀", "프로젝트")
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, off_topic, db_only(AMBIGUOUS_DB_JSON)])

    result = asyncio.run(LlmService(settings(), client=client).analyze(requirement_request()))

    assert result.database_impact.status.value == "NEEDS_CLARIFICATION"
    assert client.chat_calls == 3
    assert "팀" in client.chat_kwargs[2]["messages"][-1]["content"]


def test_clarification_question_must_address_requested_entity():
    off_topic_question = db_only(AMBIGUOUS_DB_JSON).replace(
        "팀은 기존 조직과 같은 개념인가요?", "프로젝트 멤버를 통합할 계획인가요?"
    )
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, off_topic_question, db_only(AMBIGUOUS_DB_JSON)])

    result = asyncio.run(LlmService(settings(), client=client).analyze(requirement_request()))

    assert result.database_impact.open_questions == ["팀은 기존 조직과 같은 개념인가요?"]
    assert client.chat_calls == 3


def test_requirement_analysis_rejects_structure_only_db_decision():
    wrong_scope = db_only(VALID_MODEL_JSON)
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, wrong_scope, wrong_scope])

    with pytest.raises(InvalidModelResponseError):
        asyncio.run(LlmService(settings(), client=client).analyze(requirement_request()))


def test_requirement_analysis_accepts_specific_new_column():
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, db_only(CONCRETE_DB_JSON)])

    result = asyncio.run(LlmService(settings(), client=client).analyze(requirement_request()))

    assert result.database_impact.proposed_changes[0].target_column == "team_id"


def test_requirement_analysis_rejects_new_column_that_already_exists():
    existing_column = db_only(CONCRETE_DB_JSON).replace('"targetColumn": "team_id"', '"targetColumn": "organization_id"')
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, existing_column, existing_column])

    with pytest.raises(InvalidModelResponseError):
        asyncio.run(LlmService(settings(), client=client).analyze(requirement_request()))


def test_ambiguous_requirement_cannot_claim_a_database_migration():
    contradictory = db_only(AMBIGUOUS_DB_JSON).replace(
        '"proposedChanges": [],',
        '"proposedChanges": [{"targetTable":"public.projects","targetColumn":"organization_id",'
        '"changeType":"ALTER_COLUMN","description":"소유 조직 변경", "rationale":"팀과 조직 관계 미정"}],',
    )
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, contradictory, contradictory])

    with pytest.raises(InvalidModelResponseError):
        asyncio.run(LlmService(settings(), client=client).analyze(requirement_request()))


def test_ambiguous_requirement_rejects_unsupported_unique_owner_constraint():
    misleading = db_only(AMBIGUOUS_DB_JSON).replace(
        "public.projects.organization_id를 그대로 사용하므로 소유권 컬럼 추가는 필요하지 않을 수 있습니다.",
        "public.projects.organization_id에 UNIQUE 제약을 추가해야 합니다.",
    )
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, misleading, misleading])

    with pytest.raises(InvalidModelResponseError):
        asyncio.run(LlmService(settings(), client=client).analyze(requirement_request()))


def test_analyze_retries_invalid_json_then_succeeds():
    client = FakeClient(chat_responses=["not-json", GENERAL_MODEL_JSON])
    service = LlmService(settings(), client=client)

    result = asyncio.run(service.analyze(analysis_request()))

    assert result.summary == "프로젝트 구조 요약"
    assert client.chat_calls == 2


def test_analyze_rejects_invalid_json_after_retry_limit():
    client = FakeClient(chat_responses=["not-json", "{}"])
    service = LlmService(settings(), client=client)

    with pytest.raises(InvalidModelResponseError):
        asyncio.run(service.analyze(analysis_request()))


def test_validation_log_code_does_not_include_model_content():
    with pytest.raises(ValidationError) as failure:
        DatabaseImpact.model_validate({
            "status": "NEEDS_CLARIFICATION",
            "explanation": "비공개 프로젝트 설명",
            "observedTables": [],
            "proposedChanges": [],
            "conditionalPaths": [],
            "openQuestions": ["팀의 의미는 무엇인가요?"],
        })

    assert LlmService._safe_validation_code(failure.value) == "DB_NEEDS_PATHS"
    assert LlmService._safe_validation_code(
        ValueError("DB impact omitted the requirement's business term: 비공개 프로젝트 설명.")
    ) == "DB_BUSINESS_TERM_MISSING"


def test_analyze_maps_ollama_timeout():
    request = httpx.Request("POST", "http://127.0.0.1:11434/api/chat")
    client = FakeClient(exception=httpx.ReadTimeout("timeout", request=request))
    service = LlmService(settings(), client=client)

    with pytest.raises(ModelTimeoutError):
        asyncio.run(service.analyze(analysis_request()))


def test_health_rejects_missing_model():
    client = FakeClient(list_response={"models": [{"model": "other:latest"}]})
    service = LlmService(settings(), client=client)

    with pytest.raises(ModelUnavailableError):
        asyncio.run(service.health())
