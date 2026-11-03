"""조건부 DB 설계와 설명 교정의 내부 확인값을 실제 입력에 대조한다."""

import re
from collections import Counter
from enum import Enum
from types import SimpleNamespace
from typing import List, Literal, Optional

from pydantic import ConfigDict, Field, model_validator

from app.models.analysis import (
    DatabaseChangeNecessity,
    DatabaseChangeType,
    DatabaseConditionalPath,
    DatabaseImpact,
    FinalAnalysisUpdate,
    FinalDatabaseFactsReview,
)
from app.models.base import ApiModel, to_camel
from app.services.source_context import explicit_requirement_text, supported_target_tables


CONDITIONAL_ERROR_PREFIX = "조건부 DB 변경 검증 실패:"
CONFIRMED_ERROR_PREFIX = "확정 DB 변경 검증 실패:"
NARRATIVE_ERROR_PREFIX = "DB 설명 교정 검증 실패:"


class DatabaseActionBasis(str, Enum):
    REQUIREMENT = "REQUIREMENT"
    INTEGRITY = "INTEGRITY"
    PERFORMANCE = "PERFORMANCE"
    OPTIONAL_DESIGN = "OPTIONAL_DESIGN"


class TargetMapping(str, Enum):
    VERIFIED = "VERIFIED"
    UNVERIFIED = "UNVERIFIED"
    NEW_TABLE = "NEW_TABLE"


class SynthesisModel(ApiModel):
    """모델 전용 확인값의 추가 필드와 누락을 허용하지 않는다."""
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")


class ConditionalAction(SynthesisModel):
    change_type: DatabaseChangeType
    target_table: str = Field(min_length=1)
    target_column: Optional[str]
    necessity: DatabaseChangeNecessity
    basis: DatabaseActionBasis
    requirement_evidence: Optional[str]
    target_mapping: TargetMapping

    @model_validator(mode="after")
    def check_target(self):
        if (self.target_table != self.target_table.strip()
                or self.target_column is not None and
                (not self.target_column.strip() or self.target_column != self.target_column.strip())):
            raise ValueError(f"{CONDITIONAL_ERROR_PREFIX} 대상 식별자가 비어 있거나 공백이 섞였습니다.")
        if self.change_type in {DatabaseChangeType.ADD_COLUMN, DatabaseChangeType.ALTER_COLUMN,
                                DatabaseChangeType.DROP_COLUMN} and self.target_column is None:
            raise ValueError(f"{CONDITIONAL_ERROR_PREFIX} 컬럼 변경에는 대상 컬럼이 필요합니다.")
        if self.change_type == DatabaseChangeType.CREATE_TABLE and self.target_column is not None:
            raise ValueError(f"{CONDITIONAL_ERROR_PREFIX} 새 테이블 추가의 대상 컬럼은 null이어야 합니다.")
        return self


class ConditionalPathActions(SynthesisModel):
    path_index: int = Field(strict=True, ge=0)
    actions: List[ConditionalAction]


class DataVerificationReview(SynthesisModel):
    """이 분석에서 직접 조사하지 않은 실제 값·실행·성능의 경계를 고정한다."""
    row_values: Literal["UNVERIFIED"]
    runtime_usage: Literal["UNVERIFIED"]
    performance: Literal["UNVERIFIED"]
    json_internal_keys: Literal["UNVERIFIED"]


class DatabaseSynthesis(DatabaseImpact):
    """공개 DB 결론에 구조 확인과 경로별 변경 분류를 덧붙인 내부 응답이다."""
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")
    database_facts_review: List[FinalDatabaseFactsReview]
    conditional_actions: List[ConditionalPathActions]
    confirmed_actions: List[ConditionalAction] = Field(default_factory=list)
    data_verification_review: DataVerificationReview


class DatabaseNarrative(SynthesisModel):
    explanation: str = Field(min_length=1)
    conditional_paths: List[DatabaseConditionalPath]
    open_questions: List[str]


class FinalSynthesisUpdate(FinalAnalysisUpdate):
    database_narrative: DatabaseNarrative
    conditional_actions_review: List[ConditionalPathActions]
    data_verification_review: DataVerificationReview


def _payload(request):
    return request if isinstance(request, dict) else request.model_dump(mode="json", by_alias=True)


def _requirement_strings(requirement):
    """사용자가 작성한 본문만 읽고 식별자·수집 메타데이터·자동 질문은 제외한다."""
    def strings(value):
        if isinstance(value, str):
            yield value
        elif isinstance(value, list):
            for item in value:
                yield from strings(item)

    for field in ("title", "refinedContent", "acceptanceCriteria", "latestFollowUpQuestion"):
        yield from strings(requirement.get(field))
    for clarification in requirement.get("clarifications", []):
        if isinstance(clarification, dict):
            yield from strings(clarification.get("answer"))
            if clarification.get("kind") == "USER_QUESTION":
                yield from strings(clarification.get("question"))


def _valid_quote(quote, requirement):
    if not isinstance(quote, str) or not quote.strip():
        return False
    # 식별자 하나의 일치만으로 업무 요구의 변경 근거를 충족했다고 보지 않는다.
    identifier = quote.strip().replace('"', "").replace("`", "")
    if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_$]*(?:\.[A-Za-z_][A-Za-z0-9_$]*)*", identifier):
        return False
    return any(quote in text for text in _requirement_strings(requirement))


def _explicit_target(table, requirement):
    return re.search(r"(?<![A-Za-z0-9_.$])" + re.escape(table) + r"(?![A-Za-z0-9_.$])",
                     explicit_requirement_text(requirement)) is not None


def validate_conditional_actions(impact, actions, request):
    """경로별 변경 대상·구분·인용을 확인한다. 인용과 설계의 의미 전체를 증명하지는 않는다."""
    def reject(message):
        raise ValueError(f"{CONDITIONAL_ERROR_PREFIX} {message}")

    payload = _payload(request)
    requirement = payload.get("sourceSummary", {}).get("changeRequirement", {})
    if not isinstance(requirement, dict):
        requirement = {}
    known = {f"{table['schema']}.{table['name']}": table
             for table in payload.get("databaseSummary", {}).get("tables", [])
             if isinstance(table, dict) and isinstance(table.get("schema"), str)
             and isinstance(table.get("name"), str)}
    supported = supported_target_tables(payload)
    expected = set(range(len(impact.conditional_paths)))
    if len(actions) != len(expected) or {path.path_index for path in actions} != expected:
        reject("조건별 경로 번호는 빠짐없이 한 번씩 작성해야 합니다.")
    for path in actions:
        new_tables = {action.target_table for action in path.actions
                      if action.change_type == DatabaseChangeType.CREATE_TABLE}
        additions = {(action.target_table, action.target_column) for action in path.actions
                     if action.change_type == DatabaseChangeType.ADD_COLUMN}
        seen = set()
        for action in path.actions:
            key = (action.change_type, action.target_table, action.target_column)
            if key in seen:
                reject("같은 경로에 같은 변경 작업이 중복됐습니다.")
            seen.add(key)
            table, column, change = action.target_table, action.target_column, action.change_type
            if change == DatabaseChangeType.CREATE_TABLE:
                if table in known:
                    reject("이미 수집한 테이블을 새 테이블로 추가할 수 없습니다.")
                mapping = TargetMapping.NEW_TABLE
            else:
                if table not in known and table not in new_tables:
                    reject("변경 대상 테이블이 수집한 구조나 같은 경로의 새 테이블에 없습니다.")
                if table in new_tables:
                    mapping = TargetMapping.NEW_TABLE
                else:
                    mapping = TargetMapping.VERIFIED if table in supported or _explicit_target(table, requirement) else TargetMapping.UNVERIFIED
            if action.target_mapping != mapping:
                reject("대상 테이블의 연결 확인값이 소스·사용자 요구 근거와 다릅니다.")
            collected_columns = known.get(table, {}).get("columns")
            columns = {item["name"] for item in collected_columns
                       if isinstance(item, dict) and isinstance(item.get("name"), str)} if isinstance(collected_columns, list) else set()
            if change == DatabaseChangeType.ADD_COLUMN and column in columns:
                reject("이미 수집한 컬럼을 새 컬럼으로 추가할 수 없습니다.")
            if change in {DatabaseChangeType.ALTER_COLUMN, DatabaseChangeType.DROP_COLUMN} and column not in columns:
                reject("변경하거나 삭제할 컬럼이 수집한 구조에 없습니다.")
            if (column is not None and change not in {DatabaseChangeType.ADD_COLUMN, DatabaseChangeType.CREATE_TABLE}
                    and column not in columns and (table, column) not in additions):
                reject("참조한 컬럼이 수집한 구조나 같은 경로의 새 컬럼에 없습니다.")
            if (change == DatabaseChangeType.ADD_INDEX and action.necessity == DatabaseChangeNecessity.REQUIRED
                    and action.basis in {DatabaseActionBasis.PERFORMANCE, DatabaseActionBasis.OPTIONAL_DESIGN}):
                reject("측정하지 않은 성능 개선이나 선택 설계만으로 인덱스를 필수로 확정할 수 없습니다.")
            if action.basis in {DatabaseActionBasis.REQUIREMENT, DatabaseActionBasis.INTEGRITY}:
                if not _valid_quote(action.requirement_evidence, requirement):
                    reject("요구 또는 무결성 근거에는 사용자가 작성한 요구 본문의 정확한 인용이 필요합니다.")
            elif action.requirement_evidence is not None and not _valid_quote(action.requirement_evidence, requirement):
                reject("기록한 요구 인용이 사용자가 작성한 본문과 일치하지 않습니다.")


def validate_confirmed_actions(impact, actions, request):
    """확정 변경안과 근거 분류를 일대일로 대조하고 조건부 설계와 같은 정책을 적용한다."""
    def reject(message):
        raise ValueError(f"{CONFIRMED_ERROR_PREFIX} {message}")

    def key(change):
        return change.change_type, change.target_table, change.target_column, change.necessity

    expected = [key(change) for change in impact.proposed_changes]
    actual = [key(action) for action in actions]
    if Counter(expected) != Counter(actual):
        reject("확정 변경안의 유형·대상·필수 구분과 근거 분류가 일대일로 일치해야 합니다.")
    # 확정 설계의 새 구조는 이 목록 안에서만 참조한다. 조건부 경로의 가정과 합치지 않는다.
    isolated = SimpleNamespace(conditional_paths=[None])
    try:
        validate_conditional_actions(isolated, [ConditionalPathActions(path_index=0, actions=actions)], request)
    except ValueError as error:
        message = str(error)
        if message.startswith(CONDITIONAL_ERROR_PREFIX):
            message = message[len(CONDITIONAL_ERROR_PREFIX):].strip()
        reject(message)


def validate_narrative_update(original_impact, narrative, expected_actions, actual_actions, request):
    """확정 상태와 변경안을 유지하면서 조건문과 경로별 분류의 변조를 차단한다."""
    def reject(message):
        raise ValueError(f"{NARRATIVE_ERROR_PREFIX} {message}")

    if (len(narrative.conditional_paths) != len(original_impact.conditional_paths)
            or any(before.condition != after.condition for before, after in
                   zip(original_impact.conditional_paths, narrative.conditional_paths))):
        reject("기존 조건의 수와 조건문을 바꿀 수 없습니다.")
    if len(narrative.open_questions) > len(original_impact.open_questions):
        reject("최종 교정에서 기존 확인 질문을 늘릴 수 없습니다.")
    before = [path.model_dump(mode="json", by_alias=True) for path in expected_actions]
    after = [path.model_dump(mode="json", by_alias=True) for path in actual_actions]
    if before != after:
        reject("조건별 변경 유형·대상·필수 구분·근거를 바꿀 수 없습니다.")
    validate_conditional_actions(original_impact, actual_actions, request)
