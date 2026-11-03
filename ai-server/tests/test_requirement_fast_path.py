import asyncio
import copy
import json

import pytest

from app.models.analysis import ProjectAnalysisRequest
from app.services.database_scope import database_review_scope, metadata_checks, review_groups
from app.services.llm_service import InvalidModelResponseError, LlmService
from tests.test_llm_service import (FakeClient, REQUIREMENT_GENERAL_MODEL_JSON, CONCRETE_DB_JSON,
                                    db_only, requirement_request, settings)


def planned_request():
    payload = requirement_request().model_dump(mode="json", by_alias=True)
    payload["databaseSummary"]["tables"][1]["foreignKeys"] = [
        {"column": "project_id", "referencedSchema": "public", "referencedTable": "projects"}]
    payload["databaseSummary"]["tables"].extend(
        {"schema": "example", "name": f"table_example_{index}", "columns": [{"name": "id", "type": "uuid"}]}
        for index in range(24))
    plan = {"targetTables": ["public.projects"], "evidenceFiles": ["src/ProjectStore.java"],
            "requiresFullReview": False, "reason": "요구사항 대상의 저장 참조를 우선 확인합니다."}
    return payload, plan


def final_update(**changes):
    general = json.loads(REQUIREMENT_GENERAL_MODEL_JSON)
    return {**{key: general[key] for key in ("summary", "plainLanguageSummary", "riskAssessment", "impactAreas", "recommendations")},
            "databaseFactsReview": [{"table": "public.projects", "columnsAvailable": True, "collectedColumnCount": 1,
                                     "includedColumnNames": ["organization_id"], "allCollectedColumnsIncluded": True,
                                     "indexesAvailable": False, "collectedIndexCount": None,
                                     "includedIndexes": [], "allCollectedIndexesIncluded": False}], **changes}


def test_verified_target_includes_incoming_foreign_keys_and_keeps_original_facts():
    payload, plan = planned_request()
    before = copy.deepcopy(payload)
    selected, coverage = database_review_scope(payload, plan)
    assert {table["name"] for table in selected} == {"projects", "project_members"}
    assert coverage["mode"] == "FOCUSED" and coverage["collectedTables"] == 26
    assert len(coverage["omittedTables"]) == 24
    assert payload == before


@pytest.mark.parametrize("change", ["unknown_target", "test_only", "tentative", "unrelated_file", "full", "missing"])
def test_scope_never_guesses_from_table_name_or_unsupported_mapping(change):
    payload, plan = planned_request()
    if change == "unknown_target":
        plan["targetTables"] = ["example.unknown"]
    elif change == "test_only":
        payload["relations"][0]["file"] = "tests/ProjectStoreTest.java"
        plan["evidenceFiles"] = ["tests/ProjectStoreTest.java"]
    elif change == "tentative":
        payload["relations"][0]["tentative"] = True
    elif change == "unrelated_file":
        plan["evidenceFiles"] = ["src/MemberStore.java"]
    elif change == "full":
        plan["requiresFullReview"] = True
    else:
        plan = None
    selected, coverage = database_review_scope(payload, plan)
    assert len(selected) == 26 and coverage["mode"] == "FULL"


def test_different_language_and_arbitrary_table_identifiers_use_the_same_scope_rules():
    payload, plan = planned_request()
    payload["relations"][0].update(file="server/storage.rb", schema="example", table="table_example_7")
    plan.update(targetTables=["example.table_example_7"], evidenceFiles=["server/storage.rb"])
    selected, coverage = database_review_scope(payload, plan)
    assert [table["name"] for table in selected] == ["table_example_7"]
    assert coverage["mode"] == "FOCUSED"


def test_fast_path_needs_three_calls_instead_of_a_full_database_batch_chain():
    payload, plan = planned_request()
    general = {**json.loads(REQUIREMENT_GENERAL_MODEL_JSON), "databaseReviewPlan": plan}
    update = final_update(summary="소스가 참조하는 대상과 연결 테이블을 확인했으며 별도 팀 식별자를 추가해야 합니다.",
                          plainLanguageSummary="담당 팀을 별도로 관리하려면 저장 구조와 기존 정보 확인이 필요합니다.")
    client = FakeClient(chat_responses=[json.dumps(general), db_only(CONCRETE_DB_JSON), json.dumps(update)])
    events = []

    async def progress(code, number):
        events.append((code, number))

    result = asyncio.run(LlmService(settings(ai_requirement_fast_path_enabled=True, ai_final_reconciliation_enabled=True), client=client)
                         .analyze(ProjectAnalysisRequest.model_validate(payload), progress))
    assert client.chat_calls == 3
    assert ("DATABASE_SCOPE_FOCUSED", 2) in events
    detail = json.loads(client.chat_kwargs[1]["messages"][1]["content"])
    assert len(detail["tables"]) == 2
    assert len(detail["reviewScope"]["omittedTables"]) == 24
    assert detail["metadataChecks"][0]["columnCount"] == 1
    assert result.summary == update["summary"]
    assert result.general_analysis.database_review_plan.target_tables == ["public.projects"]
    assert result.database_review_coverage["mode"] == "FOCUSED"
    assert "databaseReviewPlan" not in result.model_dump(mode="json", by_alias=True)
    assert result.database_impact.proposed_changes[0].necessity == "REQUIRED"
    assert all(call["think"] == "medium" and call["options"] == {"temperature": 0} for call in client.chat_kwargs)


def test_final_update_cannot_inject_new_db_decision_or_uncollected_files():
    payload, plan = planned_request()
    general = {**json.loads(REQUIREMENT_GENERAL_MODEL_JSON), "databaseReviewPlan": plan}
    update = final_update(summary="확인한 내용을 정리합니다.", plainLanguageSummary="변경 전 검토가 필요합니다.",
                          evidences=[{"file": "invented/unknown.js", "line": 999, "description": "새로 만든 근거"}])
    client = FakeClient(chat_responses=[json.dumps(general), db_only(CONCRETE_DB_JSON), json.dumps(update)])
    with pytest.raises(InvalidModelResponseError):
        asyncio.run(LlmService(settings(ai_requirement_fast_path_enabled=True, ai_final_reconciliation_enabled=True, ai_max_validation_retries=0), client=client)
                    .analyze(ProjectAnalysisRequest.model_validate(payload)))


def test_bad_general_file_evidence_is_repaired_before_any_expensive_db_review():
    payload, plan = planned_request()
    general = {**json.loads(REQUIREMENT_GENERAL_MODEL_JSON), "databaseReviewPlan": plan,
               "evidences": [{"file": "databaseSummary", "description": "파일이 아닌 메타데이터를 잘못 인용했습니다."}]}
    client = FakeClient(chat_responses=[json.dumps(general), json.dumps({"evidences": []}), db_only(CONCRETE_DB_JSON)])
    result = asyncio.run(LlmService(settings(ai_requirement_fast_path_enabled=True), client=client).analyze(ProjectAnalysisRequest.model_validate(payload)))
    assert client.chat_calls == 3
    assert set(client.chat_kwargs[1]["format"]["properties"]) == {"evidences"}
    assert "tables" in json.loads(client.chat_kwargs[2]["messages"][1]["content"])
    assert result.general_analysis.evidences == []


def test_final_repair_only_replaces_bad_evidence_and_keeps_the_completed_db_decision():
    payload, plan = planned_request()
    general = {**json.loads(REQUIREMENT_GENERAL_MODEL_JSON), "databaseReviewPlan": plan}
    update = final_update(summary="확인한 DB 변경안을 반영한 요약입니다.", plainLanguageSummary="저장 구조를 바꾸기 전 기존 정보를 확인해야 합니다.",
                          evidences=[{"file": "invented/unknown.js", "line": 999, "description": "잘못된 파일 근거"}])
    client = FakeClient(chat_responses=[json.dumps(general), db_only(CONCRETE_DB_JSON), json.dumps(update), json.dumps({"evidences": []})])
    result = asyncio.run(LlmService(settings(ai_requirement_fast_path_enabled=True, ai_final_reconciliation_enabled=True), client=client)
                         .analyze(ProjectAnalysisRequest.model_validate(payload)))
    assert client.chat_calls == 4
    assert set(client.chat_kwargs[-1]["format"]["properties"]) == {"evidences"}
    assert result.summary == update["summary"]
    assert result.evidences == []
    assert result.database_impact.proposed_changes[0].necessity == "REQUIRED"
    assert result.general_analysis.database_review_plan.target_tables == plan["targetTables"]


def test_insufficient_focused_metadata_expands_once_without_losing_the_original_request():
    payload, plan = planned_request()
    before = copy.deepcopy(payload)
    general = json.dumps({**json.loads(REQUIREMENT_GENERAL_MODEL_JSON), "databaseReviewPlan": plan})
    unknown = json.dumps({"status": "UNKNOWN", "explanation": "선택한 범위의 구조만으로 전체 변경 여부를 확인하지 못했습니다.",
                          "observedTables": [], "proposedChanges": [], "conditionalPaths": [], "openQuestions": []})
    client = FakeClient(chat_responses=[general, unknown, unknown])
    result = asyncio.run(LlmService(settings(ai_requirement_fast_path_enabled=True, ai_db_batch_max_tables=40), client=client)
                         .analyze(ProjectAnalysisRequest.model_validate(payload)))
    assert client.chat_calls == 3
    assert len(json.loads(client.chat_kwargs[1]["messages"][1]["content"])["tables"]) == 2
    assert len(json.loads(client.chat_kwargs[2]["messages"][1]["content"])["tables"]) == 26
    assert result.database_review_coverage["mode"] == "FULL"
    assert len(result.database_review_coverage["expandedFrom"]) == 2
    assert payload == before


def test_no_table_scope_does_not_emit_an_invalid_zero_count_progress_event():
    payload = requirement_request().model_dump(mode="json", by_alias=True)
    payload["databaseSummary"]["tables"] = []
    unknown = json.dumps({"status": "UNKNOWN", "explanation": "검토할 테이블 근거가 없어 변경 여부를 확인하지 못했습니다.",
                          "observedTables": [], "proposedChanges": [], "conditionalPaths": [], "openQuestions": []})
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, unknown])
    events = []

    async def progress(code, number):
        events.append((code, number))

    asyncio.run(LlmService(settings(ai_requirement_fast_path_enabled=True), client=client).analyze(ProjectAnalysisRequest.model_validate(payload), progress))
    assert ("DATABASE_SCOPE_FULL", None) in events
    assert all(number != 0 for _, number in events)


def test_small_reviews_are_merged_once_without_a_fixed_group_count():
    reviews = [{"tables": [f"example.table_{index}"], "result": {"status": "UNKNOWN"}} for index in range(5)]
    assert review_groups(reviews, 24000) == [reviews]
    groups = review_groups(reviews, 1)
    assert len(groups) == 3 and sum(map(len, groups)) == 5


def test_full_fallback_merges_five_small_batches_once_and_keeps_every_table():
    payload, plan = planned_request()
    plan["requiresFullReview"] = True
    general = json.dumps({**json.loads(REQUIREMENT_GENERAL_MODEL_JSON), "databaseReviewPlan": plan})
    unknown = json.dumps({"status": "UNKNOWN", "explanation": "현재 수집 근거만으로 전체 변경 여부를 확정할 수 없습니다.",
                          "observedTables": [], "proposedChanges": [], "conditionalPaths": [], "openQuestions": []})
    client = FakeClient(chat_responses=[general, *[unknown] * 6])
    result = asyncio.run(LlmService(settings(ai_requirement_fast_path_enabled=True), client=client)
                         .analyze(ProjectAnalysisRequest.model_validate(payload)))
    assert client.chat_calls == 7
    final = json.loads(client.chat_kwargs[-1]["messages"][1]["content"])
    assert len(final["reviews"]) == 5
    assert len(final["coverage"]["reviewedTables"]) == 26
    assert result.database_review_coverage["mode"] == "FULL"
    assert result.database_impact.status == "UNKNOWN"


def test_structural_checks_do_not_turn_missing_metadata_into_absence():
    facts = metadata_checks([{"schema": "example", "name": "one"},
                             {"schema": "example", "name": "two", "columns": [{"name": "id"}, {"name": "id"}], "primaryKey": ["missing"]}])
    assert facts[0]["columnMetadataAvailable"] is False
    assert facts[0]["primaryKeyColumnsPresent"] is None
    assert facts[1]["duplicateColumnNames"] == ["id"]
    assert facts[1]["primaryKeyColumnsPresent"] is False
