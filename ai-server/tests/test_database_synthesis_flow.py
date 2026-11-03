import asyncio
import json

import pytest

from app.models.analysis import ProjectAnalysisRequest
from app.services.database_synthesis import DatabaseSynthesis, FinalSynthesisUpdate
from app.services.llm_service import InvalidModelResponseError, LlmService
from app.services.response_repairs import repair_contract
from app.services.database_scope import metadata_checks
from tests.test_llm_service import FakeClient, REQUIREMENT_GENERAL_MODEL_JSON, _fixture_database_facts_review


UNVERIFIED = {"rowValues": "UNVERIFIED", "runtimeUsage": "UNVERIFIED",
              "performance": "UNVERIFIED", "jsonInternalKeys": "UNVERIFIED"}


def generic_request(index_count=0, indexes_available=True):
    primary = {"schema": "tenant_space", "name": "records", "columns": [
        {"name": "key_id", "type": "text", "nullable": False},
        {"name": "attributes", "type": "JSON", "nullable": True}],
        "primaryKey": ["key_id"], "foreignKeys": []}
    if indexes_available:
        primary["indexes"] = [{"name": f"lookup_{i}", "columns": ["key_id"], "unique": i % 2 == 0}
                              for i in range(index_count)]
    return ProjectAnalysisRequest.model_validate({
        "projectId": "00000000-0000-0000-0000-000000000123",
        "sourceSummary": {"changeRequirement": {
            "title": "항목의 선택 속성 관리", "refinedContent": "선택 속성을 저장하고 검색 조건과 함께 조회한다."}},
        "databaseSummary": {"type": "OTHER", "tables": [primary,
            {"schema": "other_space", "name": "activity", "columns": [], "indexes": [], "primaryKey": [], "foreignKeys": []}]},
        "relations": [],
    })


def decision(**changes):
    return {"status": "UNKNOWN", "explanation": "수집한 구조만으로 실제 저장 경로를 확인하지 못했습니다.",
            "observedTables": [], "proposedChanges": [], "conditionalPaths": [], "openQuestions": [], **changes}


def config(**changes):
    from tests.test_llm_service import settings
    return settings(ai_requirement_fast_path_enabled=True, **changes)


def final_response(**changes):
    general = json.loads(REQUIREMENT_GENERAL_MODEL_JSON)
    return {**{key: general[key] for key in ("summary", "plainLanguageSummary", "riskAssessment", "impactAreas", "recommendations")}, **changes}


@pytest.mark.parametrize("count", [0, 1, 3, 8, 17])
def test_merge_keeps_arbitrary_index_lists_and_never_exposes_internal_confirmation(count):
    request = generic_request(count)
    before = request.model_dump(mode="json", by_alias=True)
    final = decision(observedTables=["tenant_space.records", "other_space.activity"])
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, *[json.dumps(decision())] * 2, json.dumps(final)])
    result = asyncio.run(LlmService(config(ai_db_batch_max_tables=1), client=client).analyze(request))
    payload = json.loads(client.chat_kwargs[-1]["messages"][1]["content"])
    facts = next(table for table in payload["databaseFacts"]["tables"] if table["table"] == "tenant_space.records")
    assert facts["indexesAvailable"] is True and facts["collectedIndexCount"] == count
    assert facts["indexes"] == request.database_summary["tables"][0]["indexes"]
    assert facts["allCollectedIndexesIncluded"] is True
    assert facts["jsonColumns"] == [{"column": "attributes", "internalKeys": "UNVERIFIED", "rowValues": "UNVERIFIED"}]
    assert payload["dataVerification"] == UNVERIFIED
    fixed = client.chat_kwargs[-1]["format"]["properties"]["databaseFactsReview"]
    assert set(fixed) == {"type", "const"}
    fixed_table = next(table for table in fixed["const"] if table["table"] == "tenant_space.records")
    assert fixed_table["collectedIndexCount"] == count
    assert fixed_table["includedIndexes"] == request.database_summary["tables"][0]["indexes"]
    assert result.database_impact.model_dump(mode="json", by_alias=True) == final
    assert "databaseFactsReview" not in json.dumps(result.model_dump(mode="json", by_alias=True))
    assert request.model_dump(mode="json", by_alias=True) == before


def test_single_table_does_not_turn_uncollected_indexes_into_an_empty_observation():
    request = generic_request(indexes_available=False)
    request.database_summary["tables"] = request.database_summary["tables"][:1]
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, json.dumps(decision())])
    asyncio.run(LlmService(config(), client=client).analyze(request))
    payload = json.loads(client.chat_kwargs[-1]["messages"][1]["content"])
    assert payload["tables"][0]["indexes"] is None
    facts = payload["databaseFacts"]["tables"][0]
    assert facts["indexesAvailable"] is False
    assert facts["collectedIndexCount"] is None and facts["allCollectedIndexesIncluded"] is False


@pytest.mark.parametrize("batch_size", [1, 6])
def test_upstream_collection_limits_remain_visible_even_when_collected_indexes_are_fully_sent(batch_size):
    request = generic_request(8)
    request.database_summary.update(truncated=True, warnings=["일부 구조를 수집하지 못했습니다."])
    decisions = [json.dumps(decision())] * (3 if batch_size == 1 else 1)
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, *decisions])
    asyncio.run(LlmService(config(ai_db_batch_max_tables=batch_size), client=client).analyze(request))
    sent = json.loads(client.chat_kwargs[-1]["messages"][1]["content"])
    assert sent["databaseCoverage"] == {"truncated": True, "warnings": request.database_summary["warnings"]}
    table = next(table for table in sent["databaseFacts"]["tables"] if table["table"] == "tenant_space.records")
    assert table["collectedIndexCount"] == 8 and table["allCollectedIndexesIncluded"] is True


def test_structural_counts_keep_missing_metadata_distinct_from_observed_empty_lists():
    missing, empty = metadata_checks([
        {"schema": "arbitrary", "name": "one", "columns": []},
        {"schema": "arbitrary", "name": "two", "columns": [], "indexes": [], "foreignKeys": [], "primaryKey": []},
    ])
    assert missing["indexCount"] is None and missing["foreignKeyCount"] is None
    assert missing["primaryKeyColumnsPresent"] is None
    assert empty["indexCount"] == 0 and empty["foreignKeyCount"] == 0
    assert empty["primaryKeyColumnsPresent"] is True
    unknown = metadata_checks([{"schema": "arbitrary", "name": "uncollected"}])[0]
    assert unknown["columnMetadataAvailable"] is False and unknown["columnCount"] is None


def test_final_step_corrects_database_narrative_and_preserves_the_decision_and_training_original():
    request = generic_request(3)
    original = decision(observedTables=["tenant_space.records"], explanation="이전 단계는 인덱스를 확인하지 못했다고 잘못 설명했습니다.")
    corrected = {"explanation": "수집한 인덱스 목록은 확인했으며 JSON 내부 키와 실제 사용 경로는 미확인입니다.",
                 "conditionalPaths": [], "openQuestions": []}
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, json.dumps(original),
                                        json.dumps(final_response(databaseNarrative=corrected))])
    result = asyncio.run(LlmService(config(ai_final_reconciliation_enabled=True), client=client).analyze(request))
    assert result.database_impact.explanation == corrected["explanation"]
    assert result.database_impact.status.value == original["status"]
    assert result.database_impact.proposed_changes == []
    assert result.general_analysis.model_dump(mode="json", by_alias=True) == json.loads(REQUIREMENT_GENERAL_MODEL_JSON)
    public = result.model_dump(mode="json", by_alias=True)
    for field in ("databaseNarrative", "databaseFactsReview", "conditionalActionsReview", "dataVerificationReview"):
        assert field not in json.dumps(public)


def test_final_response_cannot_replace_the_database_status():
    request = generic_request(2)
    invalid = final_response(databaseNarrative={"status": "NO_CHANGE", "explanation": "상태를 몰래 변경하는 잘못된 설명입니다.",
                                               "conditionalPaths": [], "openQuestions": []})
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, json.dumps(decision()), json.dumps(invalid)])
    with pytest.raises(InvalidModelResponseError):
        asyncio.run(LlmService(config(ai_final_reconciliation_enabled=True, ai_max_validation_retries=0), client=client).analyze(request))


def test_final_step_receives_facts_for_tables_referenced_only_by_conditional_actions():
    original = decision(status="NEEDS_CLARIFICATION", observedTables=["tenant_space.records"], conditionalPaths=[
        {"condition": "다른 저장 경로를 선택하는 경우", "databaseEffect": "선택한 경로의 인덱스 개선을 권장 수준으로 검토합니다."},
        {"condition": "현재 저장 경로를 유지하는 경우", "databaseEffect": "현재 저장 구조의 사용 경로를 확인합니다."}],
        openQuestions=["실제 사용하는 저장 경로는 무엇인가요?"], conditionalActions=[
            {"pathIndex": 0, "actions": [{"changeType": "ADD_INDEX", "targetTable": "other_space.activity",
                "targetColumn": None, "necessity": "RECOMMENDED", "basis": "OPTIONAL_DESIGN",
                "requirementEvidence": None, "targetMapping": "UNVERIFIED"}]}, {"pathIndex": 1, "actions": []}])
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, json.dumps(original), json.dumps(final_response())])
    asyncio.run(LlmService(config(ai_final_reconciliation_enabled=True), client=client).analyze(generic_request(2)))
    sent = json.loads(client.chat_kwargs[-1]["messages"][1]["content"])
    assert {table["table"] for table in sent["reconciliationFacts"]["tables"]} == {"tenant_space.records", "other_space.activity"}
    assert sent["conditionalActions"][0]["actions"][0]["necessity"] == "RECOMMENDED"


def test_added_final_questions_trigger_related_text_repair_without_repeating_database_work(caplog):
    original = decision(observedTables=["tenant_space.records"])
    narrative = {key: original[key] for key in ("explanation", "conditionalPaths", "openQuestions")}
    invalid = final_response(databaseNarrative={**narrative, "openQuestions": ["요구와 무관한 내부 값도 추가로 확인할까요?"]})
    repair = final_response(databaseNarrative=narrative, conditionalActionsReview=[])
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, json.dumps(original), json.dumps(invalid), json.dumps(repair)])
    result = asyncio.run(LlmService(config(ai_final_reconciliation_enabled=True), client=client).analyze(generic_request(7)))
    assert client.chat_calls == 4
    fields = set(client.chat_kwargs[-1]["format"]["properties"])
    assert {"databaseNarrative", "conditionalActionsReview", "summary", "riskAssessment", "recommendations"} <= fields
    assert "FINAL_DB_NARRATIVE_MISMATCH" in caplog.text
    assert result.database_impact.open_questions == []
    assert result.database_impact.status.value == original["status"]


def test_mismatched_index_confirmation_is_rejected_instead_of_returning_a_result():
    class WrongIndexClient(FakeClient):
        async def chat(self, **kwargs):
            response = await super().chat(**kwargs)
            if "conditionalActions" in kwargs["format"].get("properties", {}):
                content = json.loads(response.message.content)
                content["databaseFactsReview"][0]["includedIndexes"][0]["unique"] = False
                response.message.content = json.dumps(content)
            return response

    client = WrongIndexClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, json.dumps(decision())])
    with pytest.raises(InvalidModelResponseError):
        asyncio.run(LlmService(config(ai_max_validation_retries=0), client=client).analyze(generic_request(3)))


def test_merge_actions_cannot_target_a_different_group_even_when_that_table_exists():
    request = generic_request(0)
    facts = LlmService._synthesis_facts(request.database_summary["tables"][:1])
    data = decision(status="NEEDS_CLARIFICATION", conditionalPaths=[
        {"condition": "다른 저장 대상을 사용하는 경우", "databaseEffect": "해당 경로에서 조건부로 컬럼 추가를 검토합니다."},
        {"condition": "현재 저장 대상을 유지하는 경우", "databaseEffect": "확인한 구조를 유지하며 실제 경로를 조사합니다."}], openQuestions=["실제 저장 대상 연결을 확인해야 합니다."])
    value = DatabaseSynthesis.model_validate({**data, "databaseFactsReview": _fixture_database_facts_review(facts),
        "dataVerificationReview": UNVERIFIED, "conditionalActions": [
            {"pathIndex": 0, "actions": [{"changeType": "ADD_COLUMN", "targetTable": "other_space.activity",
                "targetColumn": "new_attribute", "necessity": "RECOMMENDED", "basis": "OPTIONAL_DESIGN",
                "requirementEvidence": None, "targetMapping": "UNVERIFIED"}]}, {"pathIndex": 1, "actions": []}]})
    with pytest.raises(ValueError, match="현재 묶음"):
        LlmService(config())._validate_synthesis(request, value, facts, {"tenant_space.records"})


def test_confirmed_changes_cannot_bypass_the_required_performance_index_check():
    request = generic_request(2)
    request.relations = [{"schema": "tenant_space", "table": "records", "file": "service/record_store.py",
                          "line": 4, "kind": "SQL_SELECT"}]
    action = {"changeType": "ADD_INDEX", "targetTable": "tenant_space.records", "targetColumn": "key_id",
              "necessity": "REQUIRED", "basis": "PERFORMANCE", "requirementEvidence": None, "targetMapping": "VERIFIED"}
    invalid = decision(status="CHANGE_REQUIRED", proposedChanges=[{
        key: action[key] for key in ("changeType", "targetTable", "targetColumn", "necessity")
    } | {"description": "성능 개선용 인덱스를 필수로 잘못 추가합니다.", "rationale": "실제 성능 측정은 하지 않았습니다."}],
        confirmedActions=[action])
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, json.dumps(invalid)])
    with pytest.raises(InvalidModelResponseError):
        asyncio.run(LlmService(config(ai_max_validation_retries=0), client=client).analyze(request))


def test_decision_repairs_keep_conditional_actions_in_sync():
    fields, _ = repair_contract(DatabaseSynthesis, ValueError("An ambiguous DB impact needs at least two concrete conditional DB paths."), "DB_NEEDS_PATHS")
    assert {"conditionalPaths", "conditionalActions"} <= set(fields)
    fields, _ = repair_contract(FinalSynthesisUpdate, ValueError("An ambiguous DB impact needs a clarification question."), "DB_NEEDS_QUESTION")
    assert {"databaseNarrative", "conditionalActionsReview", "summary", "riskAssessment", "recommendations"} <= set(fields)


def test_fact_repairs_review_affected_narrative_in_addition_to_the_confirmation():
    fields, _ = repair_contract(DatabaseSynthesis, ValueError("최종 정리의 DB 구조 확인값이 전달한 사실과 다릅니다."), "FINAL_DB_FACTS_MISMATCH")
    assert {"databaseFactsReview", "explanation", "conditionalPaths", "conditionalActions"} <= set(fields)
    fields, _ = repair_contract(FinalSynthesisUpdate, ValueError("최종 정리의 DB 구조 확인값이 전달한 사실과 다릅니다."), "FINAL_DB_FACTS_MISMATCH")
    assert {"databaseFactsReview", "databaseNarrative", "summary", "recommendations", "riskAssessment"} <= set(fields)
