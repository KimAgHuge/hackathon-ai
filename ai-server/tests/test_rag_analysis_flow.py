import asyncio
import copy
import json

import pytest

from app.models.analysis import DatabaseImpact, ProjectAnalysisRequest, RequirementGeneralAnalysisResponse
from app.services.llm_service import InvalidModelResponseError, LlmService
from app.services.source_context import evidence_references, source_context
from tests.test_evidence_constraints import file_branches
from tests.test_llm_service import FakeClient, REQUIREMENT_GENERAL_MODEL_JSON, requirement_request, settings
from tests.test_requirement_fast_path import planned_request


class ProjectingRetriever:
    embed = None

    def __init__(self, table_names, route_file=None):
        self.table_names = set(table_names)
        self.route_file = route_file
        self.inputs = []

    async def retrieve(self, payload):
        self.inputs.append(copy.deepcopy(payload))
        reduced = copy.deepcopy(payload)
        all_tables = reduced["databaseSummary"]["tables"]
        reduced["databaseSummary"]["tables"] = [table for table in all_tables
            if f"{table['schema']}.{table['name']}" in self.table_names]
        reduced["databaseSummary"]["truncated"] = len(reduced["databaseSummary"]["tables"]) < len(all_tables)
        reduced["relations"] = [item for item in reduced.get("relations", [])
                                if f"{item.get('schema')}.{item.get('table')}" in self.table_names]
        coverage = reduced["sourceSummary"].setdefault("promptCoverage", {})
        coverage.update(truncated=True, retrieval={"mode": "RAG"})
        if self.route_file is not None:
            graph = reduced["sourceSummary"]["sourceConnections"]
            graph["routes"] = [item for item in graph["routes"] if item["file"] == self.route_file]
            graph["truncated"] = True
        return reduced


def decision(**changes):
    return {"status": "UNKNOWN", "explanation": "수집된 구조만으로 요구사항의 전체 영향을 확정하지 못했습니다.",
            "observedTables": ["public.projects"], "proposedChanges": [],
            "conditionalPaths": [], "openQuestions": [], **changes}


def final_reply(**changes):
    general = json.loads(REQUIREMENT_GENERAL_MODEL_JSON)
    return {**{key: general[key] for key in ("summary", "plainLanguageSummary", "riskAssessment", "impactAreas", "recommendations")},
            **changes}


def config(**changes):
    values = {"ai_requirement_fast_path_enabled": True, "ai_db_batch_max_tables": 40,
              "ai_db_batch_max_chars": 90000, "ai_max_validation_retries": 0}
    return settings(**{**values, **changes})


def sent_payload(client, index):
    return json.loads(client.chat_kwargs[index]["messages"][1]["content"])


def general_payload(client):
    return json.loads(client.chat_kwargs[0]["messages"][1]["content"].split(
        "Analyze these project facts and return only the required JSON object:\n", 1)[1])


def route_request():
    payload = requirement_request().model_dump(mode="json", by_alias=True)
    payload["sourceSummary"]["sourceConnections"] = {
        "links": [], "routes": [{"file": f"ui/view_{number}.vue", "line": number + 1,
                                  "route": f"/views/{number}", "kind": "ROUTE"} for number in range(70)],
        "unresolvedReferences": [], "truncated": False,
    }
    return ProjectAnalysisRequest.model_validate(payload)


def test_rag_omissions_do_not_turn_a_partial_database_into_the_full_review_scope():
    payload, plan = planned_request()
    payload["databaseSummary"].update(truncated=False, warnings=[])
    plan["requiresFullReview"] = True
    request = ProjectAnalysisRequest.model_validate(payload)
    before = request.model_dump(mode="json", by_alias=True)
    retriever = ProjectingRetriever(["public.projects"])
    general = {**json.loads(REQUIREMENT_GENERAL_MODEL_JSON), "databaseReviewPlan": plan}
    client = FakeClient(chat_responses=[json.dumps(general), json.dumps(decision())])

    result = asyncio.run(LlmService(config(), client=client, retriever=retriever).analyze(request))

    assert len(general_payload(client)["databaseSummary"]["tables"]) == 1
    detail = sent_payload(client, 1)
    assert len(detail["tables"]) == 26
    assert detail["reviewScope"]["mode"] == "FULL"
    assert detail["reviewScope"]["collectedTables"] == 26
    assert len(detail["reviewScope"]["reviewedTables"]) == 26
    assert detail["databaseCoverage"] == {"truncated": False, "warnings": []}
    assert result.database_review_coverage["mode"] == "FULL"
    assert result.database_review_coverage["collectedTables"] == 26
    assert request.model_dump(mode="json", by_alias=True) == before
    assert retriever.inputs == [before]


def test_disabling_focused_review_still_uses_the_original_database_after_rag():
    payload, _ = planned_request()
    payload["databaseSummary"].update(truncated=False, warnings=[])
    request = ProjectAnalysisRequest.model_validate(payload)
    before = request.model_dump(mode="json", by_alias=True)
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, json.dumps(decision())])

    asyncio.run(LlmService(config(ai_requirement_fast_path_enabled=False), client=client,
                           retriever=ProjectingRetriever(["public.projects"])).analyze(request))

    assert len(general_payload(client)["databaseSummary"]["tables"]) == 1
    assert len(sent_payload(client, 1)["tables"]) == 26
    assert sent_payload(client, 1)["databaseCoverage"] == {"truncated": False, "warnings": []}
    assert request.model_dump(mode="json", by_alias=True) == before


def test_unknown_focused_review_expands_to_original_evidence_and_validates_a_target_outside_rag():
    payload, plan = planned_request()
    outside_target = "example.table_example_23"
    outside_evidence = {"file": "other/store.rb", "line": 19, "description": "원래 수집한 저장 대상 참조를 확인했습니다."}
    payload["relations"].append({"file": outside_evidence["file"], "line": 19,
                                "schema": "example", "table": "table_example_23", "kind": "SQL_SELECT"})
    request = ProjectAnalysisRequest.model_validate(payload)
    before = request.model_dump(mode="json", by_alias=True)
    general = {**json.loads(REQUIREMENT_GENERAL_MODEL_JSON), "databaseReviewPlan": plan}
    changed = decision(status="CHANGE_REQUIRED", observedTables=[outside_target], proposedChanges=[{
        "changeType": "ADD_COLUMN", "targetTable": outside_target, "targetColumn": "team_id", "necessity": "REQUIRED",
        "description": "담당 팀을 구분할 새 컬럼을 추가합니다.", "rationale": "요구사항의 팀 소유권을 기록하기 위해 필요합니다.",
    }])
    client = FakeClient(chat_responses=[json.dumps(general), json.dumps(decision()), json.dumps(changed),
                                      json.dumps(final_reply(evidences=[outside_evidence]))])
    events = []

    async def progress(code, number):
        events.append((code, number))

    result = asyncio.run(LlmService(config(ai_final_reconciliation_enabled=True), client=client,
                                   retriever=ProjectingRetriever(["public.projects"])).analyze(request, progress))

    assert outside_target not in {f"{table['schema']}.{table['name']}" for table in general_payload(client)["databaseSummary"]["tables"]}
    focused, expanded, final = [sent_payload(client, index) for index in (1, 2, 3)]
    assert len(focused["tables"]) == 2 and focused["reviewScope"]["mode"] == "FOCUSED"
    assert len(expanded["tables"]) == 26 and expanded["reviewScope"]["mode"] == "FULL"
    assert expanded["reviewScope"]["expandedFrom"] == ["public.project_members", "public.projects"]
    assert ("DATABASE_SCOPE_FOCUSED", 2) in events and ("DATABASE_SCOPE_FULL", 26) in events
    assert outside_target in final["supportedTargetTables"]
    assert next(table for table in final["reconciliationFacts"]["tables"] if table["table"] == outside_target)["columns"] == [{"name": "id", "type": "uuid"}]
    assert outside_evidence["file"] in file_branches(client.chat_kwargs[-1]["format"])
    assert result.database_impact.proposed_changes[0].target_table == outside_target
    assert result.evidences == [outside_evidence]
    assert result.database_review_coverage["collectedTables"] == 26
    assert request.model_dump(mode="json", by_alias=True) == before


@pytest.mark.parametrize("final_line", [70, None, "omitted"])
def test_late_route_evidence_selected_by_rag_survives_the_full_context_route_limit(final_line):
    request = route_request()
    before = request.model_dump(mode="json", by_alias=True)
    late = {"file": "ui/view_69.vue", "line": 70, "description": "일반 분석에 실제 전달된 화면 주소 선언을 확인했습니다."}
    assert (late["file"], late["line"]) not in evidence_references(before, source_context(before))
    general = {**json.loads(REQUIREMENT_GENERAL_MODEL_JSON), "evidences": [late]}
    final_evidence = {**late, "line": final_line}
    if final_line == "omitted":
        final_evidence.pop("line")
    client = FakeClient(chat_responses=[json.dumps(general), json.dumps(decision()),
                                      json.dumps(final_reply(evidences=[final_evidence]))])

    result = asyncio.run(LlmService(config(ai_final_reconciliation_enabled=True), client=client,
                                   retriever=ProjectingRetriever(["public.projects"], late["file"])).analyze(request))

    assert general_payload(client)["sourceSummary"]["sourceContext"]["routes"][0]["file"] == late["file"]
    final = sent_payload(client, 2)
    assert all(item["file"] != late["file"] for item in final["sourceContext"]["routes"])
    assert final["generalEvidenceReferences"] == [{"file": late["file"], "line": None},
                                                 {"file": late["file"], "line": late["line"]}]
    assert file_branches(client.chat_kwargs[-1]["format"])[late["file"]]["properties"]["line"]["enum"] == [None, 70]
    assert result.evidences == [final_evidence] and result.general_analysis.evidences == [late]
    assert request.model_dump(mode="json", by_alias=True) == before


@pytest.mark.parametrize("invalid", [
    {"file": "ui/view_69.vue", "line": 70},
    {"file": "ui/view_0.vue", "line": 999},
    {"file": "invented/not_delivered.vue", "line": 3},
])
def test_a_model_citation_not_delivered_in_general_input_is_not_trusted_as_a_carried_reference(invalid):
    request = route_request()
    before = request.model_dump(mode="json", by_alias=True)
    delivered = copy.deepcopy(before)
    delivered["sourceSummary"]["sourceConnections"]["routes"] = delivered["sourceSummary"]["sourceConnections"]["routes"][:1]
    general_input = LlmService._general_overview(ProjectAnalysisRequest.model_validate(delivered))
    general = RequirementGeneralAnalysisResponse.model_validate({
        **json.loads(REQUIREMENT_GENERAL_MODEL_JSON), "evidences": [invalid],
    })
    client = FakeClient(chat_responses=[json.dumps(final_reply())])
    service = LlmService(config(ai_final_reconciliation_enabled=True), client=client)

    with pytest.raises(InvalidModelResponseError):
        asyncio.run(service._reconcile(request, general, DatabaseImpact.model_validate(decision()), None,
                                       general_input=general_input))

    final = sent_payload(client, 0)
    assert final["generalEvidenceReferences"] == []
    branches = file_branches(client.chat_kwargs[0]["format"])
    assert invalid["file"] not in branches or invalid["line"] not in branches[invalid["file"]]["properties"]["line"]["enum"]
    assert request.model_dump(mode="json", by_alias=True) == before


def test_failed_retrieval_falls_back_to_the_original_request_without_leaking_mutations():
    class FailingRetriever:
        embed = None

        async def retrieve(self, payload):
            payload["databaseSummary"]["tables"].clear()
            payload["sourceSummary"]["changeRequirement"]["title"] = "원본에 반영되면 안 되는 변경"
            raise RuntimeError("검색 저장소의 가상 오류")

    request = requirement_request()
    before = request.model_dump(mode="json", by_alias=True)
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, json.dumps(decision())])

    result = asyncio.run(LlmService(config(), client=client, retriever=FailingRetriever()).analyze(request))

    general = general_payload(client)
    assert len(general["databaseSummary"]["tables"]) == 2
    assert general["sourceSummary"]["changeRequirement"] == before["sourceSummary"]["changeRequirement"]
    assert len(sent_payload(client, 1)["tables"]) == 2
    assert result.database_review_coverage["collectedTables"] == 2
    assert request.model_dump(mode="json", by_alias=True) == before
