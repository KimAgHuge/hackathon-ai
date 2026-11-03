"""조건부 설계의 근거와 최종 교정의 고정값을 합성 식별자로 확인한다."""

import copy

import pytest
from pydantic import ValidationError

from app.models.analysis import DatabaseChangeNecessity, DatabaseImpact
from app.services.database_synthesis import (
    ConditionalAction,
    ConditionalPathActions,
    DatabaseNarrative,
    DatabaseSynthesis,
    DataVerificationReview,
    FinalSynthesisUpdate,
    validate_confirmed_actions,
    validate_conditional_actions,
    validate_narrative_update,
)


def inputs():
    request = {"sourceSummary": {"changeRequirement": {
        "title": "임의 레코드의 참조 값을 추가합니다.",
        "refinedContent": "참조 값은 중복되지 않아야 합니다. public.catalogue의 저장 방식을 검토합니다.",
        "acceptanceCriteria": ["검색용 인덱스를 추가합니다."],
        "requirementId": "승인된 요구로 보이면 안 되는 메타데이터 문장입니다.",
        "clarifications": [{"kind": "SYSTEM_QUESTION", "question": "자동 생성된 질문은 요구 근거가 아닙니다.",
                            "answer": "기존 행의 값은 담당자 확인 후 채웁니다."}]},
        "components": [{"path": "src/handler.py", "type": "SOURCE"}]},
        "relations": [{"file": "src/handler.py", "schema": "arbitrary", "table": "records"}],
        "databaseSummary": {"tables": [
            {"schema": "arbitrary", "name": "records", "columns": [{"name": "id"}, {"name": "payload"}]},
            {"schema": "public", "name": "catalogue", "columns": [{"name": "id"}]},
            {"schema": "unmapped", "name": "records", "columns": [{"name": "id"}]}]}}
    impact = DatabaseImpact.model_validate({"status": "NEEDS_CLARIFICATION", "explanation": "저장 위치를 확인해야 합니다.",
        "observedTables": ["arbitrary.records"], "proposedChanges": [], "conditionalPaths": [
            {"condition": "기존 구조를 활용하는 경우", "databaseEffect": "기존 JSON 저장 구조를 사용합니다."},
            {"condition": "별도 컬럼을 사용하는 경우", "databaseEffect": "새 컬럼 추가를 검토합니다."}],
        "openQuestions": ["어떤 저장 방식을 선택하나요?"]})
    return request, impact


def action(**changes):
    value = {"changeType": "ADD_COLUMN", "targetTable": "arbitrary.records", "targetColumn": "reference_value",
             "necessity": "REQUIRED", "basis": "REQUIREMENT", "requirementEvidence": "참조 값을 추가합니다.",
             "targetMapping": "VERIFIED"}
    value.update(changes)
    return ConditionalAction.model_validate(value)


def paths(*items):
    return [ConditionalPathActions(path_index=0, actions=[]), ConditionalPathActions(path_index=1, actions=list(items))]


def verification():
    return {"rowValues": "UNVERIFIED", "runtimeUsage": "UNVERIFIED", "performance": "UNVERIFIED",
            "jsonInternalKeys": "UNVERIFIED"}


def synthesis_payload():
    _, impact = inputs()
    return {**impact.model_dump(mode="json", by_alias=True), "databaseFactsReview": [],
            "conditionalActions": [item.model_dump(mode="json", by_alias=True) for item in paths(action())],
            "dataVerificationReview": verification()}


def final_payload():
    _, impact = inputs()
    return {"summary": "확인한 구조의 범위 안에서 저장 방식을 검토해야 합니다.",
        "plainLanguageSummary": "저장 위치를 확인하면 변경 범위를 정할 수 있습니다.",
        "riskAssessment": {name: {"score": 0, "reason": "합성 검사의 범위만 반영합니다."} for name in
                           ("changeScope", "dataImpact", "securityImpact", "deliveryComplexity", "uncertainty")},
        "impactAreas": [], "recommendations": [], "databaseFactsReview": [],
        "databaseNarrative": {key: value for key, value in impact.model_dump(mode="json", by_alias=True).items()
                              if key in {"explanation", "conditionalPaths", "openQuestions"}},
        "conditionalActionsReview": [item.model_dump(mode="json", by_alias=True) for item in paths(action())],
        "dataVerificationReview": verification()}


def test_internal_schemas_require_nullable_fields_and_reject_extra_claims():
    DatabaseSynthesis.model_validate(synthesis_payload())
    FinalSynthesisUpdate.model_validate(final_payload())
    for key in ("targetColumn", "requirementEvidence", "targetMapping"):
        value = action().model_dump(mode="json", by_alias=True)
        value.pop(key)
        with pytest.raises(ValidationError):
            ConditionalAction.model_validate(value)
    for payload, model in ((synthesis_payload(), DatabaseSynthesis), (final_payload(), FinalSynthesisUpdate)):
        payload["dataVerificationReview"]["invented"] = "VERIFIED"
        with pytest.raises(ValidationError):
            model.model_validate(payload)


@pytest.mark.parametrize("field", ["rowValues", "runtimeUsage", "performance", "jsonInternalKeys"])
@pytest.mark.parametrize("value", ["VERIFIED", True, None])
def test_uninspected_row_runtime_performance_and_json_facts_cannot_be_promoted(field, value):
    review = verification()
    review[field] = value
    with pytest.raises(ValidationError):
        DataVerificationReview.model_validate(review)


@pytest.mark.parametrize("value", [True, 1.0, "1", -1])
def test_path_index_is_a_nonnegative_strict_integer(value):
    with pytest.raises(ValidationError):
        ConditionalPathActions(pathIndex=value, actions=[])


def test_empty_paths_and_verified_generic_targets_preserve_original_input():
    request, impact = inputs()
    before = copy.deepcopy((request, impact))
    validate_conditional_actions(impact, paths(action()), request)
    validate_conditional_actions(impact, paths(action(targetTable="public.catalogue")), request)
    validate_conditional_actions(impact, paths(), request)
    assert (request, impact) == before


def test_unmapped_existing_table_is_only_an_unverified_conditional_candidate():
    request, impact = inputs()
    validate_conditional_actions(impact, paths(action(targetTable="unmapped.records", targetMapping="UNVERIFIED")), request)
    with pytest.raises(ValueError, match="조건부 DB 변경 검증 실패:.*연결 확인값"):
        validate_conditional_actions(impact, paths(action(targetTable="unmapped.records")), request)


@pytest.mark.parametrize("mention,verified", [
    ('"unmapped.records"', True), ("`unmapped.records`", True), ("(unmapped.records)", True),
    ("prefixunmapped.records", False), ("unmapped.records_suffix", False),
    ("unmapped.records.child", False), ("unmapped.records$", False),
])
def test_explicit_schema_targets_respect_quotes_and_identifier_boundaries(mention, verified):
    request, impact = inputs()
    request["sourceSummary"]["changeRequirement"]["refinedContent"] = f"저장 대상은 {mention}입니다."
    mapping = "VERIFIED" if verified else "UNVERIFIED"
    validate_conditional_actions(impact, paths(action(targetTable="unmapped.records", targetMapping=mapping)), request)
    wrong_mapping = "UNVERIFIED" if verified else "VERIFIED"
    with pytest.raises(ValueError, match="조건부 DB 변경 검증 실패:.*연결 확인값"):
        validate_conditional_actions(impact, paths(action(targetTable="unmapped.records", targetMapping=wrong_mapping)), request)


@pytest.mark.parametrize("value", [[], [ConditionalPathActions(pathIndex=0, actions=[])],
    [ConditionalPathActions(pathIndex=0, actions=[]), ConditionalPathActions(pathIndex=0, actions=[])],
    [ConditionalPathActions(pathIndex=0, actions=[]), ConditionalPathActions(pathIndex=2, actions=[])]])
def test_each_conditional_path_requires_exactly_one_review(value):
    request, impact = inputs()
    with pytest.raises(ValueError, match="조건부 DB 변경 검증 실패:.*경로 번호"):
        validate_conditional_actions(impact, value, request)


def test_new_table_and_column_references_are_local_to_one_conditional_path():
    request, impact = inputs()
    create = action(changeType="CREATE_TABLE", targetTable="novel.records", targetColumn=None, targetMapping="NEW_TABLE")
    column = action(targetTable="novel.records", targetMapping="NEW_TABLE")
    index = action(changeType="ADD_INDEX", targetTable="novel.records", necessity="RECOMMENDED",
                   basis="PERFORMANCE", requirementEvidence=None, targetMapping="NEW_TABLE")
    validate_conditional_actions(impact, paths(create, column, index), request)
    for item in (column, index):
        wrong = item.model_copy(update={"target_mapping": "UNVERIFIED"})
        with pytest.raises(ValueError, match="조건부 DB 변경 검증 실패:.*연결 확인값"):
            validate_conditional_actions(impact, paths(create, column, wrong) if item is index else paths(create, wrong), request)
    split = [ConditionalPathActions(pathIndex=0, actions=[create]), ConditionalPathActions(pathIndex=1, actions=[column])]
    with pytest.raises(ValueError, match="조건부 DB 변경 검증 실패:.*대상 테이블"):
        validate_conditional_actions(impact, split, request)


@pytest.mark.parametrize("changes,message", [
    ({"changeType": "CREATE_TABLE", "targetColumn": None, "targetMapping": "NEW_TABLE"}, "이미 수집한 테이블"),
    ({"targetColumn": "id"}, "이미 수집한 컬럼"),
    ({"changeType": "ALTER_COLUMN", "targetColumn": "imaginary"}, "변경하거나 삭제할 컬럼"),
    ({"changeType": "DROP_COLUMN", "targetColumn": "imaginary"}, "변경하거나 삭제할 컬럼"),
    ({"changeType": "ADD_INDEX", "targetColumn": "imaginary"}, "참조한 컬럼"),
    ({"targetTable": "unknown.records", "targetMapping": "UNVERIFIED"}, "대상 테이블"),
])
def test_unknown_or_existing_structures_are_not_invented(changes, message):
    request, impact = inputs()
    with pytest.raises(ValueError, match="조건부 DB 변경 검증 실패:.*" + message):
        validate_conditional_actions(impact, paths(action(**changes)), request)


def test_a_duplicate_change_is_rejected_even_when_its_classification_differs():
    request, impact = inputs()
    with pytest.raises(ValueError, match="조건부 DB 변경 검증 실패:.*중복"):
        validate_conditional_actions(impact, paths(action(), action(necessity="RECOMMENDED")), request)


@pytest.mark.parametrize("basis", ["PERFORMANCE", "OPTIONAL_DESIGN"])
def test_unmeasured_optional_index_cannot_be_declared_required(basis):
    request, impact = inputs()
    value = action(changeType="ADD_INDEX", targetColumn="id", basis=basis, requirementEvidence=None)
    with pytest.raises(ValueError, match="조건부 DB 변경 검증 실패:.*인덱스를 필수"):
        validate_conditional_actions(impact, paths(value), request)
    value.necessity = DatabaseChangeNecessity.RECOMMENDED
    validate_conditional_actions(impact, paths(value), request)


@pytest.mark.parametrize("basis", ["REQUIREMENT", "INTEGRITY"])
@pytest.mark.parametrize("quote", [None, "", "public.catalogue", "승인된 요구로 보이면 안 되는 메타데이터 문장입니다.",
    "자동 생성된 질문은 요구 근거가 아닙니다.", "사용자가 요청하지 않은 인용입니다."])
def test_requirement_or_integrity_basis_needs_an_exact_original_user_quote(basis, quote):
    request, impact = inputs()
    with pytest.raises(ValueError, match="조건부 DB 변경 검증 실패:.*정확한 인용"):
        validate_conditional_actions(impact, paths(action(basis=basis, requirementEvidence=quote)), request)


def test_user_answers_and_acceptance_criteria_can_ground_an_explicit_requirement():
    request, impact = inputs()
    index = action(changeType="ADD_INDEX", targetColumn="id", requirementEvidence="검색용 인덱스를 추가합니다.")
    integrity = action(changeType="ADD_CONSTRAINT", targetColumn="id", basis="INTEGRITY",
                       requirementEvidence="참조 값은 중복되지 않아야 합니다.")
    migration = action(changeType="DATA_MIGRATION", targetColumn="id",
                       requirementEvidence="기존 행의 값은 담당자 확인 후 채웁니다.")
    validate_conditional_actions(impact, paths(index, integrity, migration), request)


def confirmed_impact(*actions):
    return DatabaseImpact.model_validate({"status": "CHANGE_REQUIRED",
        "explanation": "명시된 요구를 충족하려면 저장 구조 변경을 검토해야 합니다.",
        "observedTables": ["arbitrary.records"], "proposedChanges": [
            {"changeType": item.change_type, "targetTable": item.target_table, "targetColumn": item.target_column,
             "necessity": item.necessity, "description": "합성 요구의 변경을 적용합니다.",
             "rationale": "합성 입력에서 명시한 요구를 따릅니다."} for item in actions],
        "conditionalPaths": [], "openQuestions": []})


@pytest.mark.parametrize("basis", ["PERFORMANCE", "OPTIONAL_DESIGN"])
def test_confirmed_required_index_cannot_bypass_the_conditional_policy(basis):
    request, _ = inputs()
    index = action(changeType="ADD_INDEX", targetColumn="id", basis=basis, requirementEvidence=None)
    impact = confirmed_impact(index)
    with pytest.raises(ValueError, match="확정 DB 변경 검증 실패:.*인덱스를 필수"):
        validate_confirmed_actions(impact, [index], request)


def test_a_confirmed_required_index_can_use_an_explicit_user_integrity_quote():
    request, _ = inputs()
    index = action(changeType="ADD_INDEX", targetColumn="id", basis="INTEGRITY",
                   requirementEvidence="참조 값은 중복되지 않아야 합니다.")
    impact = confirmed_impact(index)
    validate_confirmed_actions(impact, [index], request)


@pytest.mark.parametrize("mutation", ["missing", "duplicate", "column", "necessity"])
def test_confirmed_classification_matches_every_proposed_change_exactly_once(mutation):
    request, _ = inputs()
    original = action()
    impact = confirmed_impact(original)
    actual = [original.model_copy(deep=True)]
    if mutation == "missing":
        actual = []
    elif mutation == "duplicate":
        actual.append(original)
    elif mutation == "column":
        actual[0].target_column = "other_value"
    else:
        actual[0].necessity = DatabaseChangeNecessity.RECOMMENDED
    with pytest.raises(ValueError, match="확정 DB 변경 검증 실패:.*일대일"):
        validate_confirmed_actions(impact, actual, request)


def test_omitted_confirmed_actions_only_defaults_safely_when_no_proposal_exists():
    request, _ = inputs()
    empty = DatabaseSynthesis.model_validate(synthesis_payload())
    assert empty.confirmed_actions == []
    validate_confirmed_actions(empty, empty.confirmed_actions, request)
    value = synthesis_payload()
    value.update(confirmed_impact(action()).model_dump(mode="json", by_alias=True))
    parsed = DatabaseSynthesis.model_validate(value)
    assert parsed.confirmed_actions == []
    with pytest.raises(ValueError, match="확정 DB 변경 검증 실패:.*일대일"):
        validate_confirmed_actions(parsed, parsed.confirmed_actions, request)


def test_confirmed_and_conditional_structures_cannot_supply_each_others_missing_columns():
    request, conditional = inputs()
    new_column = action()
    index = action(changeType="ADD_INDEX", necessity="RECOMMENDED", basis="PERFORMANCE", requirementEvidence=None)
    confirmed = confirmed_impact(new_column, index)
    validate_confirmed_actions(confirmed, [index, new_column], request)
    validate_conditional_actions(conditional, paths(new_column), request)
    required_index = action(changeType="ADD_INDEX", requirementEvidence="검색용 인덱스를 추가합니다.")
    with pytest.raises(ValueError, match="확정 DB 변경 검증 실패:.*참조한 컬럼"):
        validate_confirmed_actions(confirmed_impact(required_index), [required_index], request)
    with pytest.raises(ValueError, match="조건부 DB 변경 검증 실패:.*참조한 컬럼"):
        validate_conditional_actions(conditional, paths(index), request)


def test_final_narrative_may_correct_effects_and_questions_without_mutating_decisions():
    request, impact = inputs()
    original = impact.model_copy(deep=True)
    expected = paths(action())
    narrative = DatabaseNarrative(explanation="수집한 구조의 사실과 사용 경로의 미확인은 별개입니다.",
        conditional_paths=[path.model_copy(deep=True) for path in impact.conditional_paths],
        open_questions=["실제 저장 위치를 확인해 주시겠어요?"])
    narrative.conditional_paths[0].database_effect = "기존 구조를 활용할 수 있는지 실행 경로를 확인합니다."
    validate_narrative_update(impact, narrative, expected, copy.deepcopy(expected), request)
    assert impact == original
    assert "status" not in DatabaseNarrative.model_fields and "proposed_changes" not in DatabaseNarrative.model_fields


@pytest.mark.parametrize("original_count", [0, 1])
def test_final_narrative_cannot_turn_additional_unverified_items_into_new_questions(original_count):
    request, impact = inputs()
    data = impact.model_dump(mode="json", by_alias=True)
    data.update(status="UNKNOWN", openQuestions=["기존 저장 위치는 어디인가요?"][:original_count])
    impact = DatabaseImpact.model_validate(data)
    narrative = DatabaseNarrative(explanation="전달한 구조와 실제 값의 확인 여부를 구분합니다.",
        conditional_paths=impact.conditional_paths,
        open_questions=[*impact.open_questions, "요청 범위와 별개인 JSON 내부 값도 확인하나요?"])
    expected = paths(action())
    with pytest.raises(ValueError, match="DB 설명 교정 검증 실패:.*질문을 늘릴 수 없습니다"):
        validate_narrative_update(impact, narrative, expected, copy.deepcopy(expected), request)


def test_final_narrative_can_remove_resolved_questions():
    request, impact = inputs()
    data = impact.model_dump(mode="json", by_alias=True)
    data.update(status="UNKNOWN", openQuestions=["구조에서 해결된 질문입니다.", "아직 남아 있는 질문입니다."])
    impact = DatabaseImpact.model_validate(data)
    narrative = DatabaseNarrative(explanation="확인한 구조로 해결된 질문을 정리합니다.",
        conditional_paths=impact.conditional_paths, open_questions=["아직 남아 있는 질문입니다."])
    expected = paths(action())
    validate_narrative_update(impact, narrative, expected, copy.deepcopy(expected), request)


@pytest.mark.parametrize("mutation", ["condition", "count", "classification", "order", "extra_status"])
def test_final_narrative_cannot_change_conditions_or_structured_decisions(mutation):
    request, impact = inputs()
    expected = paths(action())
    actual = copy.deepcopy(expected)
    payload = {key: value for key, value in impact.model_dump(mode="json", by_alias=True).items()
               if key in {"explanation", "conditionalPaths", "openQuestions"}}
    if mutation == "condition":
        payload["conditionalPaths"][0]["condition"] = "원래 입력과 다른 조건입니다."
    elif mutation == "count":
        payload["conditionalPaths"].pop()
    elif mutation == "classification":
        actual[1].actions[0].necessity = DatabaseChangeNecessity.RECOMMENDED
    elif mutation == "order":
        actual.reverse()
    else:
        payload["status"] = "CHANGE_REQUIRED"
        with pytest.raises(ValidationError):
            DatabaseNarrative.model_validate(payload)
        return
    with pytest.raises(ValueError, match="DB 설명 교정 검증 실패:"):
        validate_narrative_update(impact, DatabaseNarrative.model_validate(payload), expected, actual, request)
