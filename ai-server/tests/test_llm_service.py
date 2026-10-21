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
    ModelOutputLimitError,
    ModelUnavailableError,
)


def settings(**overrides):
    values = {
        "ai_internal_api_key": "test-internal-api-key-at-least-32-characters",
        "ai_max_validation_retries": 1,
        # 이 모듈은 기존 두 단계 계약을 분리 검사한다. 최종 정리는 별도 테스트에서 켠다.
        "ai_final_reconciliation_enabled": False,
        "ai_requirement_fast_path_enabled": False,
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
            "relations": [{"schema": "public", "table": "projects", "file": "src/ProjectStore.java", "line": 3, "kind": "ORM_TABLE"},
                          {"schema": "public", "table": "project_members", "file": "src/MemberStore.java", "line": 5, "kind": "SQL_SELECT"}],
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
  "plainLanguageSummary": "현재 시스템의 구성을 살펴본 결과이며, 아직 변경 작업을 결정한 것은 아닙니다.",
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
  "plainLanguageSummary": "프로젝트 관리 담당을 한 팀으로 제한하려면 팀의 의미를 먼저 확인해야 합니다.",
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
  "plainLanguageSummary": "프로젝트마다 담당 팀을 정하는 방식으로 바뀔 수 있어 기존 정보 확인이 필요합니다.",
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
      "necessity": "REQUIRED",
      "description": "팀 식별자 컬럼을 추가합니다.",
      "rationale": "프로젝트마다 관리 팀을 명시하기 위해서입니다."
    }],
    "conditionalPaths": [],
    "openQuestions": []
  }
}"""


ZERO_RISK_ASSESSMENT = {
    "changeScope": {"score": 0, "reason": "변경 범위를 확인하지 못했습니다."},
    "dataImpact": {"score": 0, "reason": "데이터 변경이 확인되지 않았습니다."},
    "securityImpact": {"score": 0, "reason": "보안 변경이 확인되지 않았습니다."},
    "deliveryComplexity": {"score": 0, "reason": "배포 변경이 확인되지 않았습니다."},
    "uncertainty": {"score": 0, "reason": "추가 정보가 필요합니다."},
}


def with_risk_assessment(response):
    payload = json.loads(response)
    payload["riskAssessment"] = ZERO_RISK_ASSESSMENT
    return json.dumps(payload, ensure_ascii=False)


VALID_MODEL_JSON = with_risk_assessment(VALID_MODEL_JSON)
AMBIGUOUS_DB_JSON = with_risk_assessment(AMBIGUOUS_DB_JSON)
CONCRETE_DB_JSON = with_risk_assessment(CONCRETE_DB_JSON)

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


def test_database_receives_source_connections_not_only_table_metadata():
    payload = requirement_request().model_dump(mode="json", by_alias=True)
    payload["sourceSummary"]["sourceConnections"] = {"links": [{
        "file": "app/routes.py", "line": 4, "targetFile": "src/ProjectStore.java", "targetLine": 3,
        "kind": "STATIC_IMPORT_REFERENCE"}]}
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, db_only(AMBIGUOUS_DB_JSON)])
    asyncio.run(LlmService(settings(), client=client).analyze(ProjectAnalysisRequest.model_validate(payload)))
    message = json.loads(client.chat_kwargs[1]["messages"][1]["content"])
    assert message["sourceContext"]["links"][0]["file"] == "app/routes.py"
    assert message["sourceContext"]["tableReferences"][0]["table"] == "projects"


def test_database_rejects_a_target_backed_only_by_table_name_similarity():
    payload = requirement_request().model_dump(mode="json", by_alias=True)
    payload["relations"] = []
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, db_only(CONCRETE_DB_JSON)])
    with pytest.raises(InvalidModelResponseError):
        asyncio.run(LlmService(settings(ai_max_validation_retries=0), client=client)
                    .analyze(ProjectAnalysisRequest.model_validate(payload)))


def test_explicit_human_qualified_table_can_support_a_target_without_source_mapping():
    payload = requirement_request().model_dump(mode="json", by_alias=True)
    payload["relations"] = []
    payload["sourceSummary"]["changeRequirement"]["refinedContent"] = "public.projects에 팀 식별자를 추가합니다."
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, db_only(CONCRETE_DB_JSON)])
    result = asyncio.run(LlmService(settings(), client=client).analyze(ProjectAnalysisRequest.model_validate(payload)))
    assert result.database_impact.proposed_changes[0].target_table == "public.projects"


def test_final_reconciliation_updates_summary_but_preserves_original_training_stage():
    final = json.loads(REQUIREMENT_GENERAL_MODEL_JSON)
    final["summary"] = "확인한 DB 구조에는 별도 팀 연결이 없어 스키마·이관 검토가 필요합니다."
    final["evidences"] = [{"file": "src/ProjectStore.java", "line": 3, "description": "관찰한 저장 대상"}]
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, db_only(CONCRETE_DB_JSON), json.dumps(final, ensure_ascii=False)])
    events = []

    async def progress(code, detail):
        events.append(code)

    result = asyncio.run(LlmService(settings(ai_final_reconciliation_enabled=True), client=client).analyze(requirement_request(), progress))
    assert result.summary == final["summary"]
    assert result.general_analysis.summary == json.loads(REQUIREMENT_GENERAL_MODEL_JSON)["summary"]
    assert "RECONCILIATION_STARTED" in events and "RECONCILIATION_COMPLETED" in events
    assert client.chat_calls == 3
    assert all("think" not in call and "num_predict" not in call.get("options", {}) for call in client.chat_kwargs)


@pytest.mark.parametrize("unrelated", [True, False])
def test_final_reconciliation_rejects_changed_relevance_or_invented_evidence(unrelated):
    final = json.loads(REQUIREMENT_GENERAL_MODEL_JSON)
    if unrelated:
        final["databaseRelevance"] = "UNRELATED"
    else:
        final["evidences"] = [{"file": "invented/Unknown.py", "line": 999, "description": "수집하지 않은 근거"}]
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, db_only(AMBIGUOUS_DB_JSON), json.dumps(final)])
    with pytest.raises(InvalidModelResponseError):
        asyncio.run(LlmService(settings(ai_final_reconciliation_enabled=True, ai_max_validation_retries=0), client=client)
                    .analyze(requirement_request()))


def test_unrelated_question_skips_database_and_final_reconciliation():
    client = FakeClient(chat_responses=[UNRELATED_GENERAL_MODEL_JSON])
    result = asyncio.run(LlmService(settings(ai_final_reconciliation_enabled=True), client=client).analyze(presentation_requirement_request()))
    assert client.chat_calls == 1
    assert result.database_impact.status == "NOT_APPLICABLE"


def test_analyze_validates_structured_response():
    client = FakeClient(chat_responses=[GENERAL_MODEL_JSON])
    service = LlmService(settings(), client=client)

    result = asyncio.run(service.analyze(analysis_request()))

    assert result.risk_level == "UNKNOWN"
    assert result.risk_score == 0
    assert result.plain_language_summary.startswith("현재 시스템의 구성")
    assert client.chat_calls == 1


def test_plain_language_summary_is_required_and_retried():
    incomplete = json.loads(GENERAL_MODEL_JSON)
    incomplete.pop("plainLanguageSummary")
    client = FakeClient(chat_responses=[json.dumps(incomplete, ensure_ascii=False), GENERAL_MODEL_JSON])

    events = []

    async def report(code, attempt):
        events.append((code, attempt))

    result = asyncio.run(LlmService(settings(), client=client).analyze(analysis_request(), progress=report))

    assert result.plain_language_summary
    assert client.chat_calls == 2
    assert "plainLanguageSummary" in client.chat_kwargs[1]["messages"][-1]["content"]
    assert ("VALIDATION_RETRY", 2) in events


def test_requirement_analysis_requires_grounded_db_decision():
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, db_only(AMBIGUOUS_DB_JSON)])
    result = asyncio.run(LlmService(settings(), client=client).analyze(requirement_request()))

    assert result.database_impact.status.value == "NEEDS_CLARIFICATION"
    assert result.database_impact.observed_tables == ["public.projects", "public.project_members"]
    assert client.chat_calls == 2
    assert result.general_analysis.database_relevance.value == "RELATED"
    assert result.general_analysis.database_relevance_reason == json.loads(REQUIREMENT_GENERAL_MODEL_JSON)["databaseRelevanceReason"]


def test_preserves_general_stage_before_database_risk_adjustment():
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, db_only(AMBIGUOUS_DB_JSON)])
    result = asyncio.run(LlmService(settings(), client=client).analyze(requirement_request()))
    assert result.general_analysis.risk_assessment.uncertainty.score == json.loads(REQUIREMENT_GENERAL_MODEL_JSON)["riskAssessment"]["uncertainty"]["score"]
    assert result.risk_assessment.uncertainty.score >= 5
    assert result.model_dump(mode="json", by_alias=True)["generalAnalysis"]["databaseRelevance"] == "RELATED"


def test_general_stage_uses_table_overview_and_database_stage_keeps_details():
    request = requirement_request()
    request.database_summary["tables"][0].update({
        "primaryKey": ["id"],
        "foreignKeys": [{"column": "organization_id", "referencedTable": "organizations"}],
        "indexes": [{"name": "projects_organization_idx"}],
    })
    original_tables = json.loads(json.dumps(request.database_summary["tables"]))
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, db_only(AMBIGUOUS_DB_JSON)])

    asyncio.run(LlmService(settings(), client=client).analyze(request))

    general_input = client.chat_kwargs[0]["messages"][1]["content"]
    database_input = client.chat_kwargs[1]["messages"][1]["content"]
    assert '"detailLevel":"TABLE_OVERVIEW_ONLY"' in general_input
    assert '"name":"projects"' in general_input
    assert '"organization_id"' not in general_input
    assert '"projects_organization_idx"' not in general_input
    assert '"organization_id"' in database_input
    assert '"projects_organization_idx"' in database_input
    assert request.database_summary["tables"] == original_tables


def test_database_keeps_model_thinking_and_generation_defaults_without_dropping_facts(caplog):
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, db_only(AMBIGUOUS_DB_JSON)])
    with caplog.at_level("INFO", logger="app.services.llm_service"):
        asyncio.run(LlmService(settings(), client=client).analyze(requirement_request()))
    assert "think" not in client.chat_kwargs[0]
    assert client.chat_kwargs[0]["options"] == {"temperature": 0}
    assert "think" not in client.chat_kwargs[1]
    assert client.chat_kwargs[1]["options"] == {"temperature": 0}
    database_input = json.loads(client.chat_kwargs[1]["messages"][1]["content"])
    assert len(database_input["tables"]) == len(requirement_request().database_summary["tables"])
    assert "model_attempt_started" in caplog.text
    assert "한 팀만 프로젝트를 관리" not in caplog.text


def test_database_validation_retry_does_not_disable_thinking_or_cap_generation():
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, "{}", db_only(AMBIGUOUS_DB_JSON)])
    asyncio.run(LlmService(settings(), client=client).analyze(requirement_request()))
    for call in client.chat_kwargs[1:]:
        assert "think" not in call
        assert call["options"] == {"temperature": 0}


def test_thinking_is_measured_without_logging_its_content(caplog):
    private_thinking = "로그에 남기면 안 되는 가상 추론 내용입니다."

    class ThinkingClient(FakeClient):
        async def chat(self, **kwargs):
            response = await super().chat(**kwargs)
            response.message.thinking = private_thinking
            return response

    client = ThinkingClient(chat_responses=[GENERAL_MODEL_JSON])
    with caplog.at_level("INFO", logger="app.services.llm_service"):
        asyncio.run(LlmService(settings(), client=client).analyze(analysis_request()))
    assert f"thinking_chars={len(private_thinking)}" in caplog.text
    assert private_thinking not in caplog.text


@pytest.mark.parametrize("dictionary_response", [False, True])
def test_output_limit_never_accepts_partial_response_or_retries_it(dictionary_response, caplog):
    class LimitedClient(FakeClient):
        async def chat(self, **kwargs):
            await super().chat(**kwargs)
            value = {"message": {"content": db_only(AMBIGUOUS_DB_JSON)}, "done_reason": "length"}
            return value if dictionary_response else SimpleNamespace(**value)

    client = LimitedClient(chat_responses=["ignored"])
    with pytest.raises(ModelOutputLimitError), caplog.at_level("WARNING"):
        asyncio.run(LlmService(settings(), client=client)._analyze_database_impact(
            requirement_request(), requirement_request().source_summary["changeRequirement"]
        ))
    assert client.chat_calls == 1
    assert "model_output_limit" in caplog.text
    assert "한 팀만 프로젝트를 관리" not in caplog.text


def test_risk_score_and_level_are_derived_from_independent_change_factors():
    general = json.loads(REQUIREMENT_GENERAL_MODEL_JSON)
    general["riskLevel"] = "HIGH"
    general["riskScore"] = 8
    general["riskAssessment"] = {
        "changeScope": {"score": 20, "reason": "여러 구성요소의 변경이 필요합니다."},
        "dataImpact": {"score": 12, "reason": "기존 데이터 이관 가능성이 있습니다."},
        "securityImpact": {"score": 22, "reason": "인증 경로 변경으로 보안 검토가 필요합니다."},
        "deliveryComplexity": {"score": 15, "reason": "배포 경로가 변경될 수 있습니다."},
        "uncertainty": {"score": 3, "reason": "일부 업무 선택이 남았습니다."},
    }
    client = FakeClient(chat_responses=[json.dumps(general, ensure_ascii=False), db_only(AMBIGUOUS_DB_JSON)])

    result = asyncio.run(LlmService(settings(), client=client).analyze(requirement_request()))

    assert result.risk_score == 74
    assert result.risk_level.value == "HIGH"
    assert result.risk_assessment.uncertainty.score == 5
    assert result.risk_assessment.security_impact.score == 22


def test_answers_can_reduce_uncertainty_without_erasing_foundational_risk():
    general = json.loads(REQUIREMENT_GENERAL_MODEL_JSON)
    general["riskAssessment"] = {
        "changeScope": {"score": 25, "reason": "핵심 구조를 변경합니다."},
        "dataImpact": {"score": 18, "reason": "기존 데이터를 이관합니다."},
        "securityImpact": {"score": 23, "reason": "권한 체계에 영향을 줍니다."},
        "deliveryComplexity": {"score": 15, "reason": "여러 배포 단계를 거칩니다."},
        "uncertainty": {"score": 0, "reason": "업무 조건이 명확합니다."},
    }
    resolved_client = FakeClient(chat_responses=[json.dumps(general, ensure_ascii=False), db_only(CONCRETE_DB_JSON)])
    resolved = asyncio.run(LlmService(settings(), client=resolved_client).analyze(requirement_request()))

    general["riskAssessment"]["uncertainty"] = {"score": 10, "reason": "핵심 업무 선택이 남았습니다."}
    unresolved_client = FakeClient(chat_responses=[json.dumps(general, ensure_ascii=False), db_only(AMBIGUOUS_DB_JSON)])
    unresolved = asyncio.run(LlmService(settings(), client=unresolved_client).analyze(requirement_request()))

    assert resolved.risk_score == 81
    assert unresolved.risk_score == 91
    assert resolved.risk_level.value == unresolved.risk_level.value == "CRITICAL"
    assert resolved.risk_assessment.uncertainty.score == 0


def test_requirement_analysis_reports_real_general_and_database_phases():
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, db_only(AMBIGUOUS_DB_JSON)])
    events = []

    async def report(code, attempt):
        events.append((code, attempt))

    asyncio.run(LlmService(settings(), client=client).analyze(requirement_request(), progress=report))

    assert events == [
        ("GENERAL_STARTED", None), ("GENERAL_COMPLETED", None),
        ("DATABASE_STARTED", None), ("DATABASE_COMPLETED", None),
    ]


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


def test_database_validation_accepts_a_paraphrased_business_concept():
    request = requirement_request()
    request.source_summary["changeRequirement"] = {
        "title": "계정 활성화 상태 확인",
        "refinedContent": "로그인 가능 여부를 저장된 값으로 확인한다.",
    }
    request.database_summary["tables"] = [
        {"schema": "public", "name": "user_profiles", "columns": [{"name": "enabled"}]},
    ]
    impact = DatabaseImpact.model_validate({
        "status": "NO_CHANGE",
        "explanation": "로그인 가능 여부는 public.user_profiles.enabled 값으로 확인할 수 있습니다.",
        "observedTables": ["public.user_profiles"],
        "proposedChanges": [],
        "conditionalPaths": [],
        "openQuestions": [],
    })

    LlmService._validate_database_impact(request, impact)


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


def test_requirement_analysis_rejects_structure_only_db_decision():
    wrong_scope = db_only(VALID_MODEL_JSON)
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, wrong_scope, wrong_scope])

    with pytest.raises(InvalidModelResponseError):
        asyncio.run(LlmService(settings(), client=client).analyze(requirement_request()))


def test_requirement_analysis_accepts_specific_new_column():
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, db_only(CONCRETE_DB_JSON)])

    result = asyncio.run(LlmService(settings(), client=client).analyze(requirement_request()))

    assert result.database_impact.proposed_changes[0].target_column == "team_id"
    assert result.risk_assessment.data_impact.score >= 5


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


def test_ambiguous_requirement_can_explain_a_constraint_without_proposing_it():
    explanation = db_only(AMBIGUOUS_DB_JSON).replace(
        "public.projects.organization_id를 그대로 사용하므로 소유권 컬럼 추가는 필요하지 않을 수 있습니다.",
        "public.projects.organization_id에 UNIQUE 제약을 추가하거나 기존 컬럼을 삭제하지 않고 관계를 유지할 수 있습니다.",
    )
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, explanation])

    result = asyncio.run(LlmService(settings(), client=client).analyze(requirement_request()))

    assert result.database_impact.proposed_changes == []
    assert client.chat_calls == 2


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
        ValueError("DB impact cites a table absent from collected metadata.")
    ) == "DB_UNKNOWN_OBSERVED_TABLE"


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
