import asyncio
import copy
import json

import pytest

from app.services.llm_service import InvalidModelResponseError, LlmService
from app.services.response_repairs import apply_repair
from tests.test_llm_service import FakeClient, GENERAL_MODEL_JSON, analysis_request, settings


def test_only_the_invalid_field_is_regenerated_and_full_result_is_validated():
    initial = json.loads(GENERAL_MODEL_JSON)
    initial.pop("plainLanguageSummary")
    original = copy.deepcopy(initial)
    client = FakeClient(chat_responses=[json.dumps(initial), json.dumps({"plainLanguageSummary": "현재 시스템의 구성을 확인한 결과입니다."})])
    result = asyncio.run(LlmService(settings(ai_requirement_fast_path_enabled=True), client=client).analyze(analysis_request()))
    assert set(client.chat_kwargs[1]["format"]["properties"]) == {"plainLanguageSummary"}
    assert result.summary == initial["summary"]
    assert initial == original
    assert "previousOutput" in client.chat_kwargs[1]["messages"][-1]["content"]
    assert "think" not in client.chat_kwargs[1]


@pytest.mark.parametrize("patch", [{"summary": "교체해서는 안 되는 내용"},
                                    {"plainLanguageSummary": "정상 문장입니다.", "summary": "추가 변경"},
                                    {"plainLanguageSummary": ""}])
def test_partial_repair_cannot_overwrite_other_fields_or_bypass_full_validation(patch):
    initial = json.loads(GENERAL_MODEL_JSON)
    initial.pop("plainLanguageSummary")
    client = FakeClient(chat_responses=[json.dumps(initial), json.dumps(patch)])
    with pytest.raises(InvalidModelResponseError):
        asyncio.run(LlmService(settings(ai_requirement_fast_path_enabled=True), client=client).analyze(analysis_request()))


def test_unparseable_json_falls_back_to_the_full_contract():
    client = FakeClient(chat_responses=["not-json", GENERAL_MODEL_JSON])
    result = asyncio.run(LlmService(settings(ai_requirement_fast_path_enabled=True), client=client).analyze(analysis_request()))
    assert "riskAssessment" in client.chat_kwargs[1]["format"]["properties"]
    assert result.summary


def test_repair_does_not_modify_the_original_object():
    source = {"summary": "보존할 문장", "evidences": []}
    result = apply_repair(source, {"summary": "수정 문장"}, ["summary"])
    assert source["summary"] == "보존할 문장" and result["summary"] == "수정 문장"


def test_final_failure_codes_distinguish_evidence_relevance_and_score_errors():
    assert LlmService._safe_validation_code(ValueError("최종 정리의 파일·줄 근거가 수집 범위에 없습니다.")) == "FINAL_EVIDENCE_NOT_OBSERVED"
    assert LlmService._safe_validation_code(ValueError("상세 DB 분석을 수행한 최종 결과를 관련 없음으로 바꿀 수 없습니다.")) == "FINAL_DB_RELEVANCE_FLIP"
    assert LlmService._safe_validation_code(ValueError("A risk factor exceeds its scoring range.")) == "RISK_FACTOR_RANGE"


def test_json_schema_exposes_actual_risk_factor_limits_before_generation():
    from app.models.analysis import RequirementGeneralAnalysisResponse
    schema = RequirementGeneralAnalysisResponse.model_json_schema(by_alias=True)
    assert schema["$defs"]["RiskFactor20"]["properties"]["score"]["maximum"] == 20
    assert schema["$defs"]["RiskFactor10"]["properties"]["score"]["maximum"] == 10
