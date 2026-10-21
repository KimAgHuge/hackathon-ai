import re
from enum import Enum
from typing import Any, Dict, List, Optional
from uuid import UUID

from pydantic import ConfigDict, Field, field_validator, model_validator

from app.models.base import ApiModel, to_camel


FORBIDDEN_SECRET_KEYS = {
    "accesstoken",
    "apikey",
    "authorization",
    "ciphertext",
    "credential",
    "dbpassword",
    "password",
    "passphrase",
    "privatekey",
    "secret",
    "token",
}


def find_forbidden_key(value: Any, path: str = "") -> Optional[str]:
    """중첩된 입력에서 비밀정보 필드명을 찾아 위치를 반환한다; 없으면 None."""
    if isinstance(value, dict):
        for key, nested_value in value.items():
            normalized_key = re.sub(r"[^a-z0-9]", "", str(key).lower())
            current_path = f"{path}.{key}" if path else str(key)
            if normalized_key in FORBIDDEN_SECRET_KEYS:
                return current_path
            found = find_forbidden_key(nested_value, current_path)
            if found:
                return found
    elif isinstance(value, list):
        for index, nested_value in enumerate(value):
            found = find_forbidden_key(nested_value, f"{path}[{index}]")
            if found:
                return found
    return None


class ProjectAnalysisRequest(ApiModel):
    """Backend가 전달하는 분석 입력; 값이 아닌 필드명 기준으로 비밀정보를 차단한다."""
    project_id: UUID
    source_summary: Dict[str, Any]
    database_summary: Dict[str, Any]
    relations: List[Dict[str, Any]] = Field(default_factory=list)

    @model_validator(mode="after")
    def reject_secrets(self):
        forbidden_path = find_forbidden_key(
            {
                "sourceSummary": self.source_summary,
                "databaseSummary": self.database_summary,
                "relations": self.relations,
            }
        )
        if forbidden_path:
            raise ValueError(
                f"민감 필드 '{forbidden_path}'는 AI 분석/추론 서버로 보낼 수 없습니다."
            )
        return self


class RiskLevel(str, Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"
    UNKNOWN = "UNKNOWN"


class RiskFactor(ApiModel):
    score: int = Field(ge=0, le=25)
    reason: str = Field(min_length=1)


class RiskFactor20(RiskFactor):
    score: int = Field(ge=0, le=20)


class RiskFactor10(RiskFactor):
    score: int = Field(ge=0, le=10)


class RiskAssessment(ApiModel):
    """변경 자체의 위험과 아직 남은 불확실성을 분리한 근거별 점수다."""
    change_scope: RiskFactor
    data_impact: RiskFactor20
    security_impact: RiskFactor
    delivery_complexity: RiskFactor20
    uncertainty: RiskFactor10

    @model_validator(mode="after")
    def check_factor_ranges(self):
        limits = {
            "change_scope": 25,
            "data_impact": 20,
            "security_impact": 25,
            "delivery_complexity": 20,
            "uncertainty": 10,
        }
        for name, maximum in limits.items():
            if getattr(self, name).score > maximum:
                raise ValueError("A risk factor exceeds its scoring range.")
        return self

def risk_level_for_score(score: int) -> RiskLevel:
    if score >= 75:
        return RiskLevel.CRITICAL
    if score >= 50:
        return RiskLevel.HIGH
    if score >= 25:
        return RiskLevel.MEDIUM
    return RiskLevel.LOW


class DatabaseRelevance(str, Enum):
    RELATED = "RELATED"
    UNRELATED = "UNRELATED"
    UNCERTAIN = "UNCERTAIN"


class DatabaseImpactStatus(str, Enum):
    CHANGE_REQUIRED = "CHANGE_REQUIRED"
    NO_CHANGE = "NO_CHANGE"
    NEEDS_CLARIFICATION = "NEEDS_CLARIFICATION"
    UNKNOWN = "UNKNOWN"
    NOT_APPLICABLE = "NOT_APPLICABLE"


class DatabaseChangeType(str, Enum):
    CREATE_TABLE = "CREATE_TABLE"
    ADD_COLUMN = "ADD_COLUMN"
    ALTER_COLUMN = "ALTER_COLUMN"
    DROP_COLUMN = "DROP_COLUMN"
    ADD_CONSTRAINT = "ADD_CONSTRAINT"
    ALTER_CONSTRAINT = "ALTER_CONSTRAINT"
    ADD_INDEX = "ADD_INDEX"
    DATA_MIGRATION = "DATA_MIGRATION"
    OTHER = "OTHER"


class DatabaseChangeNecessity(str, Enum):
    REQUIRED = "REQUIRED"
    RECOMMENDED = "RECOMMENDED"


class DatabaseChange(ApiModel):
    target_table: str = Field(min_length=1)
    target_column: Optional[str]
    change_type: DatabaseChangeType
    description: str = Field(min_length=1)
    rationale: str = Field(min_length=1)
    # 구형 저장 결과에는 구분값을 만들어 넣지 않는다. 새 추론에서는 별도로 필수 검사를 한다.
    necessity: Optional[DatabaseChangeNecessity] = Field(default=None, exclude_if=lambda value: value is None)

    @model_validator(mode="after")
    def check_column_action(self):
        if self.change_type in {
            DatabaseChangeType.ADD_COLUMN,
            DatabaseChangeType.ALTER_COLUMN,
            DatabaseChangeType.DROP_COLUMN,
        } and not self.target_column:
            raise ValueError("A column change needs the exact target column.")
        return self


class DatabaseConditionalPath(ApiModel):
    condition: str = Field(min_length=1)
    database_effect: str = Field(min_length=1)


class DatabaseImpact(ApiModel):
    """DB 변경 판단, 근거 테이블, 확정 변경안 또는 확인 질문을 담는다."""
    status: DatabaseImpactStatus
    explanation: str = Field(min_length=1)
    observed_tables: List[str]
    proposed_changes: List[DatabaseChange]
    conditional_paths: List[DatabaseConditionalPath]
    open_questions: List[str]

    @model_validator(mode="after")
    def check_decision(self):
        additions = set()
        for change in self.proposed_changes:
            if change.change_type in {DatabaseChangeType.ADD_COLUMN, DatabaseChangeType.CREATE_TABLE}:
                key = (change.change_type, change.target_table,
                       change.target_column if change.change_type == DatabaseChangeType.ADD_COLUMN else None)
                if key in additions:
                    raise ValueError("같은 테이블 또는 컬럼을 중복해서 추가할 수 없습니다.")
                additions.add(key)
        classified = [change.necessity for change in self.proposed_changes]
        if any(value is not None for value in classified):
            if any(value is None for value in classified):
                raise ValueError("변경안의 필수·권장 구분을 모두 작성해야 합니다.")
            if self.status == DatabaseImpactStatus.CHANGE_REQUIRED and DatabaseChangeNecessity.REQUIRED not in classified:
                raise ValueError("권장 개선만으로 DB 변경 필수를 확정할 수 없습니다.")
        if self.status == DatabaseImpactStatus.CHANGE_REQUIRED and not self.proposed_changes:
            raise ValueError("A required DB change needs a concrete proposed change.")
        if self.status == DatabaseImpactStatus.NEEDS_CLARIFICATION and not self.open_questions:
            raise ValueError("An ambiguous DB impact needs a clarification question.")
        if self.status == DatabaseImpactStatus.NEEDS_CLARIFICATION and len(self.conditional_paths) < 2:
            raise ValueError("An ambiguous DB impact needs at least two concrete conditional DB paths.")
        if self.status != DatabaseImpactStatus.CHANGE_REQUIRED and self.proposed_changes:
            raise ValueError("Only confirmed required DB changes may include proposed changes.")
        return self


class GeneralAnalysisResponse(ApiModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )

    summary: str
    plain_language_summary: str = Field(min_length=1, max_length=200)
    frameworks: List[str]
    risk_level: RiskLevel
    risk_score: int = Field(ge=0, le=100)
    risk_assessment: RiskAssessment
    impact_areas: List[str]
    evidences: List[Dict[str, Any]]
    recommendations: List[str]

    @field_validator("plain_language_summary")
    @classmethod
    def validate_plain_language_summary(cls, value: str) -> str:
        sentence = value.strip()
        if not sentence or "\n" in sentence or "\r" in sentence:
            raise ValueError("A plain-language summary must be a single non-empty line.")
        return sentence


class DatabaseReviewPlan(ApiModel):
    """모델이 제안한 우선 검토 범위다. 서버에서 실제 소스·DB 근거와 대조한 뒤 사용한다."""
    target_tables: List[str]
    evidence_files: List[str]
    requires_full_review: bool
    reason: str = Field(min_length=1)


class RequirementGeneralAnalysisResponse(GeneralAnalysisResponse):
    database_relevance: DatabaseRelevance
    database_relevance_reason: str = Field(min_length=1)
    database_review_plan: Optional[DatabaseReviewPlan] = Field(default=None, exclude_if=lambda value: value is None)


class FinalDatabaseFactsReview(ApiModel):
    """마지막 단계가 실제로 받은 컬럼 근거를 읽었는지 대조하는 내부 확인값이다."""
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")
    table: str = Field(min_length=1)
    columns_available: bool = Field(strict=True)
    collected_column_count: Optional[int] = Field(ge=0, strict=True)
    included_column_names: List[str]
    all_collected_columns_included: bool = Field(strict=True)


class FinalAnalysisUpdate(ApiModel):
    """DB 검토 후 설명·위험·권장 사항을 필수로 정리한다. DB 결론 자체는 바꾸지 않는다."""
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")
    summary: str = Field(min_length=1)
    plain_language_summary: str = Field(min_length=1, max_length=200)
    risk_assessment: RiskAssessment
    impact_areas: List[str]
    evidences: Optional[List[Dict[str, Any]]] = None
    recommendations: List[str]
    database_facts_review: List[FinalDatabaseFactsReview]


class ProjectAnalysisResponse(GeneralAnalysisResponse):
    """구조 분석과 요구사항 분석 모두에 적용되는 최종 API 응답 형식."""
    analysis_model: Optional[str] = Field(default=None, exclude_if=lambda value: value is None)
    analysis_source_model: Optional[str] = Field(default=None, exclude_if=lambda value: value is None)
    analysis_adapter_digest: Optional[str] = Field(default=None, exclude_if=lambda value: value is None)
    analysis_trained_model_digest: Optional[str] = Field(default=None, exclude_if=lambda value: value is None)
    database_impact: DatabaseImpact
    # DB 분석 후 조정한 최종 결과와 구분해 학습 초안에 쓸 원래 단계 응답을 보존한다.
    general_analysis: Optional[RequirementGeneralAnalysisResponse] = None
    database_review_coverage: Optional[Dict[str, Any]] = Field(default=None, exclude_if=lambda value: value is None)


class ModelHealthResponse(ApiModel):
    status: str
    model: str
    available: bool
