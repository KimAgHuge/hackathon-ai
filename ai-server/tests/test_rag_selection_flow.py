import asyncio
import json

from app.models.analysis import ProjectAnalysisRequest
from app.services.evidence_retriever import EvidenceRetriever, EvidenceStore
from app.services.llm_service import LlmService
from tests.test_llm_service import FakeClient, REQUIREMENT_GENERAL_MODEL_JSON, requirement_request, settings

UNKNOWN_DB = json.dumps({"status": "UNKNOWN", "explanation": "근거가 부족해 판단할 수 없습니다.", "observedTables": [],
                         "proposedChanges": [], "conditionalPaths": [], "openQuestions": []}, ensure_ascii=False)


def rag_request(requirement):
    payload = requirement_request().model_dump(mode="json", by_alias=True)
    payload["sourceSummary"]["changeRequirement"] = {**requirement, "evidenceSelection": "ALWAYS"}
    payload["databaseSummary"]["tables"].extend(
        {"schema": "example", "name": f"table_example_{index}", "columns": [{"name": "id", "type": "uuid"}]} for index in range(30))
    return ProjectAnalysisRequest.model_validate(payload)


def service(tmp_path, client):
    return LlmService(settings(ai_requirement_fast_path_enabled=True), client=client,
                      retriever=EvidenceRetriever(EvidenceStore(tmp_path / "index.db")))


def test_database_review_uses_only_the_tables_selected_by_rag_and_never_widens(tmp_path):
    request = rag_request({"title": "projects 팀 한정", "refinedContent": "projects organization_id 컬럼을 한 팀으로 제한한다."})
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, UNKNOWN_DB])
    result = asyncio.run(service(tmp_path, client).analyze(request))

    # 일반 분석 1회 + DB 1회. 결과가 UNKNOWN이어도 전체 테이블로 다시 돌지 않는다.
    assert client.chat_calls == 2
    detail = json.loads(client.chat_kwargs[1]["messages"][1]["content"])
    assert [table["name"] for table in detail["tables"]] == ["projects"]
    assert result.database_review_coverage["mode"] == "FOCUSED"
    assert result.database_review_coverage["collectedTables"] == 32
    assert result.database_review_coverage["reviewedTables"] == ["public.projects"]


def test_no_related_evidence_skips_the_database_model_call_and_says_so(tmp_path):
    request = rag_request({"title": "한 팀만 관리", "refinedContent": "프로젝트 관리를 하나의 팀으로 제한한다."})
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON])
    result = asyncio.run(service(tmp_path, client).analyze(request))

    assert client.chat_calls == 1
    general_prompt = client.chat_kwargs[0]["messages"][1]["content"]
    assert "관련된 근거를 찾지 못" in general_prompt and "table_example_7" not in general_prompt
    assert result.database_impact.status.value == "UNKNOWN"
    assert "관련 근거 없음" in result.database_impact.explanation
    assert result.database_review_coverage["reviewedTables"] == []
