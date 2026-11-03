import asyncio
import copy
import json

import pytest
from pydantic import ValidationError

from app.models.analysis import FinalAnalysisUpdate
from app.services.llm_service import InvalidModelResponseError, LlmService
from tests.test_llm_service import AMBIGUOUS_DB_JSON, FakeClient, REQUIREMENT_GENERAL_MODEL_JSON, requirement_request, settings
from tests.test_requirement_fast_path import final_update


def scenario():
    general = json.loads(REQUIREMENT_GENERAL_MODEL_JSON)
    general["summary"] = "수집 구조를 확인하지 못한 이전 요약입니다."
    general["riskAssessment"]["dataImpact"] = {"score": 8, "reason": "컬럼 목록이 미확인인 이전 판단입니다."}
    general["riskAssessment"]["securityImpact"] = {"score": 20, "reason": "접근 권한 변경 자체의 위험이 남아 있습니다."}
    general["riskAssessment"]["uncertainty"] = {"score": 9, "reason": "소스와 컬럼이 모두 미확인인 이전 판단입니다."}
    general["impactAreas"] = ["스키마 변경이 확정됐다는 이전 설명입니다."]
    general["recommendations"] = ["컬럼 목록을 다시 수집하라는 이전 권고입니다."]
    impact = json.loads(AMBIGUOUS_DB_JSON)["databaseImpact"]
    impact["observedTables"] = ["public.projects"]
    update = final_update(summary="컬럼은 확인됐으며 업무 의미와 소스 동작은 추가 확인이 필요합니다.",
                          plainLanguageSummary="저장 방식에 따라 필요한 작업이 달라져 선택을 확인해야 합니다.",
                          impactAreas=["저장 방식에 따라 스키마 변경 여부가 달라집니다."],
                          recommendations=["확인된 컬럼을 다시 수집하지 말고 저장 방식과 실제 권한 경로를 검토하세요."])
    update["riskAssessment"] = {
        "changeScope": {"score": 12, "reason": "변경 자체의 범위는 유지됩니다."},
        "dataImpact": {"score": 6, "reason": "수집 컬럼은 확인했으나 실제 행의 값과 저장 방식은 미확인입니다."},
        "securityImpact": {"score": 20, "reason": "접근 권한 변경 자체의 위험은 구조 확인만으로 줄이지 않습니다."},
        "deliveryComplexity": {"score": 8, "reason": "스키마를 바꾸는 경로라면 배포 검토가 필요합니다."},
        "uncertainty": {"score": 6, "reason": "컬럼 미확인은 해소됐지만 업무 선택과 실행 경로는 남아 있습니다."},
    }
    return general, impact, update


def test_final_required_sections_replace_old_explanations_without_rewriting_db_or_training_original():
    general, impact, update = scenario()
    before = copy.deepcopy((general, impact, update))
    client = FakeClient(chat_responses=[json.dumps(general), json.dumps(impact), json.dumps(update)])
    result = asyncio.run(LlmService(settings(ai_requirement_fast_path_enabled=True, ai_final_reconciliation_enabled=True), client=client)
                         .analyze(requirement_request()))
    assert client.chat_calls == 3
    assert result.general_analysis.model_dump(mode="json", by_alias=True) == general
    assert result.database_impact.model_dump(mode="json", by_alias=True) == impact
    assert result.risk_assessment.data_impact.reason == update["riskAssessment"]["dataImpact"]["reason"]
    assert result.risk_assessment.security_impact.score == general["riskAssessment"]["securityImpact"]["score"]
    assert result.risk_assessment.uncertainty.score == 6
    assert result.risk_score == 52 and result.risk_level.value == "HIGH"
    assert result.impact_areas == update["impactAreas"]
    assert result.recommendations == update["recommendations"]
    assert "databaseFactsReview" not in result.model_dump(mode="json", by_alias=True)
    message = json.loads(client.chat_kwargs[-1]["messages"][1]["content"])
    assert message["reconciliationFacts"]["tables"][0]["columns"] == [{"name": "organization_id"}]
    assert message["reconciliationFacts"]["rowValuesVerified"] is False
    assert message["reconciliationFacts"]["runtimeUsageVerified"] is False
    assert (general, impact, update) == before
    assert all(call["think"] == "medium" and "num_predict" not in call.get("options", {}) for call in client.chat_kwargs)


@pytest.mark.parametrize("field", ["riskAssessment", "impactAreas", "recommendations", "databaseFactsReview"])
@pytest.mark.parametrize("missing", [True, False])
def test_omitting_or_nulling_a_final_review_section_is_rejected(field, missing):
    _, _, update = scenario()
    if missing:
        update.pop(field)
    else:
        update[field] = None
    with pytest.raises(ValidationError):
        FinalAnalysisUpdate.model_validate(update)
    schema = FinalAnalysisUpdate.model_json_schema(by_alias=True)
    assert field in schema["required"]


def test_wrong_column_count_repairs_related_explanations_without_restarting_database_review(caplog):
    general, impact, update = scenario()
    correct_reviews = copy.deepcopy(update["databaseFactsReview"])
    update["databaseFactsReview"][0]["collectedColumnCount"] = 11
    repair = {key: update[key] for key in ("summary", "plainLanguageSummary", "riskAssessment", "impactAreas", "recommendations")}
    repair["databaseFactsReview"] = correct_reviews
    repair["databaseNarrative"] = {key: impact[key] for key in ("explanation", "conditionalPaths", "openQuestions")}
    client = FakeClient(chat_responses=[json.dumps(general), json.dumps(impact), json.dumps(update), json.dumps(repair)])
    result = asyncio.run(LlmService(settings(ai_requirement_fast_path_enabled=True, ai_final_reconciliation_enabled=True), client=client)
                         .analyze(requirement_request()))
    assert client.chat_calls == 4
    assert set(client.chat_kwargs[-1]["format"]["properties"]) == set(repair)
    assert client.chat_kwargs[-1]["format"]["properties"]["databaseFactsReview"] == client.chat_kwargs[-2]["format"]["properties"]["databaseFactsReview"]
    assert "FINAL_DB_FACTS_MISMATCH" in caplog.text
    assert result.summary == update["summary"] and result.recommendations == update["recommendations"]
    assert result.database_impact.model_dump(mode="json", by_alias=True) == impact


def test_new_mandatory_section_can_be_repaired_without_regenerating_other_final_fields():
    general, impact, update = scenario()
    assessment = update.pop("riskAssessment")
    client = FakeClient(chat_responses=[json.dumps(general), json.dumps(impact), json.dumps(update), json.dumps({"riskAssessment": assessment})])
    result = asyncio.run(LlmService(settings(ai_requirement_fast_path_enabled=True, ai_final_reconciliation_enabled=True), client=client)
                         .analyze(requirement_request()))
    assert set(client.chat_kwargs[-1]["format"]["properties"]) == {"riskAssessment"}
    assert result.risk_score == 52
    assert result.summary == update["summary"]


def test_bad_final_structure_is_not_marked_successful_if_the_model_repeats_it():
    general, impact, update = scenario()
    update["databaseFactsReview"][0]["includedColumnNames"] = ["invented_column"]
    client = FakeClient(chat_responses=[json.dumps(general), json.dumps(impact), json.dumps(update),
                                        json.dumps({"databaseFactsReview": update["databaseFactsReview"]})])
    with pytest.raises(InvalidModelResponseError):
        asyncio.run(LlmService(settings(ai_requirement_fast_path_enabled=True, ai_final_reconciliation_enabled=True), client=client)
                    .analyze(requirement_request()))
