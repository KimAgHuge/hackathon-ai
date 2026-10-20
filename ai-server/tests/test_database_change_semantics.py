"""DB 변경안의 유형·필수 구분·중복과 구형 결과 호환성을 합성 응답으로 확인한다."""

import asyncio
import copy
import json

import pytest
from pydantic import ValidationError

from app.models.analysis import DatabaseImpact
from app.services.llm_service import DB_SYSTEM_PROMPT, InvalidModelResponseError, LlmService
from app.services.stage_checkpoints import StageCheckpoints
from tests.test_database_batches import checkpoint_client
from tests.test_llm_service import CONCRETE_DB_JSON, FakeClient, db_only, requirement_request, settings


def concrete():
    return json.loads(CONCRETE_DB_JSON)["databaseImpact"]


def index_change(column="team_id"):
    return {"targetTable": "public.projects", "targetColumn": column, "changeType": "ADD_INDEX",
            "necessity": "RECOMMENDED", "description": "검색 비용을 확인한 뒤 인덱스 추가를 검토합니다.",
            "rationale": "실제 실행 계획과 데이터 분포가 없으므로 선택적인 성능 개선입니다."}


@pytest.mark.parametrize("legacy", [False, True])
def test_duplicate_column_additions_are_rejected_even_in_legacy_answers(legacy):
    answer = concrete()
    answer["proposedChanges"] *= 2
    if legacy:
        for change in answer["proposedChanges"]:
            change.pop("necessity", None)
    with pytest.raises(ValidationError, match="중복"):
        DatabaseImpact.model_validate(answer)


def test_index_is_separate_from_column_addition_and_can_use_a_column_in_the_same_plan():
    answer = concrete()
    answer["proposedChanges"].append(index_change())
    parsed = DatabaseImpact.model_validate(answer)
    LlmService._validate_database_impact(requirement_request(), parsed)
    assert parsed.proposed_changes[1].change_type.value == "ADD_INDEX"
    assert parsed.proposed_changes[1].necessity.value == "RECOMMENDED"


def test_index_cannot_cite_a_column_absent_from_metadata_and_the_plan():
    answer = concrete()
    answer["proposedChanges"].append(index_change("column_example_missing"))
    with pytest.raises(ValueError, match="인덱스 대상 컬럼"):
        LlmService._validate_database_impact(requirement_request(), DatabaseImpact.model_validate(answer))


def test_optional_index_alone_is_not_a_required_database_migration():
    answer = concrete()
    answer["proposedChanges"] = [index_change("organization_id")]
    with pytest.raises(ValidationError, match="권장 개선만"):
        DatabaseImpact.model_validate(answer)


def test_legacy_other_and_missing_necessity_are_read_without_filling_in_new_claims():
    answer = concrete()
    answer["proposedChanges"][0].pop("necessity")
    answer["proposedChanges"][0]["changeType"] = "OTHER"
    parsed = DatabaseImpact.model_validate(answer)
    assert parsed.model_dump(mode="json", by_alias=True) == answer
    with pytest.raises(ValueError, match="필수·권장"):
        LlmService._validate_database_impact(requirement_request(), parsed)


def test_new_inference_retries_recommendation_only_instead_of_claiming_required_change():
    wrong = concrete()
    wrong["proposedChanges"] = [index_change("organization_id")]
    correct = {"status": "NO_CHANGE", "explanation": "관찰한 구조로 처리할 수 있으며 인덱스는 실행 계획 확인 후 검토합니다.",
               "observedTables": ["public.projects"], "proposedChanges": [], "conditionalPaths": [], "openQuestions": []}
    client = FakeClient(chat_responses=[json.dumps(wrong, ensure_ascii=False), json.dumps(correct, ensure_ascii=False)])
    result = asyncio.run(LlmService(settings(), client=client)._analyze_database_impact(
        requirement_request(), requirement_request().source_summary["changeRequirement"]))
    assert result.status.value == "NO_CHANGE"
    assert client.chat_calls == 2
    assert "권장 개선만" in client.chat_kwargs[1]["messages"][-1]["content"]


def test_invalid_saved_proposal_is_rechecked_and_generated_again(tmp_path):
    store = StageCheckpoints(tmp_path / "stages", "test-only-signing-key")
    request = requirement_request()
    first = checkpoint_client([db_only(CONCRETE_DB_JSON)])
    asyncio.run(LlmService(settings(), client=first, checkpoints=store)._analyze_database_impact(
        request, request.source_summary["changeRequirement"]))
    key = next(store.directory.glob("*.json")).stem
    wrong = concrete()
    wrong["proposedChanges"].append(copy.deepcopy(wrong["proposedChanges"][0]))
    assert store.write(key, wrong)
    second = checkpoint_client([db_only(CONCRETE_DB_JSON)])
    result = asyncio.run(LlmService(settings(), client=second, checkpoints=store)._analyze_database_impact(
        request, request.source_summary["changeRequirement"]))
    assert second.chat_calls == 1
    assert len(result.proposed_changes) == 1


def test_changed_prompt_does_not_reuse_an_older_semantic_review(tmp_path, monkeypatch):
    store = StageCheckpoints(tmp_path / "stages", "test-only-signing-key")
    request = requirement_request()
    first = checkpoint_client([db_only(CONCRETE_DB_JSON)])
    asyncio.run(LlmService(settings(), client=first, checkpoints=store)._analyze_database_impact(
        request, request.source_summary["changeRequirement"]))
    monkeypatch.setattr("app.services.llm_service.DB_SYSTEM_PROMPT", DB_SYSTEM_PROMPT + "\n추가 검증 규칙입니다.")
    second = checkpoint_client([db_only(CONCRETE_DB_JSON)])
    asyncio.run(LlmService(settings(), client=second, checkpoints=store)._analyze_database_impact(
        request, request.source_summary["changeRequirement"]))
    assert second.chat_calls == 1


def test_common_database_rules_do_not_invent_a_project_specific_mapping():
    for rule in ("SQL NULL", "JSON 내부 null", "JSON 키가 없는", "NOT NULL", "ADD_INDEX", "RECOMMENDED", "실제 측정 근거"):
        assert rule in DB_SYSTEM_PROMPT
    for table in ("organization_members", "change_requests", "target_completion_date"):
        assert table not in DB_SYSTEM_PROMPT
