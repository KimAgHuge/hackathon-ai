import re
from enum import Enum
from typing import Any, Dict, List, Optional
from uuid import UUID

from pydantic import ConfigDict, Field, model_validator

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
                f"Sensitive field '{forbidden_path}' must not be sent to the AI server."
            )
        return self


class RiskLevel(str, Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"
    UNKNOWN = "UNKNOWN"


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
    DATA_MIGRATION = "DATA_MIGRATION"
    OTHER = "OTHER"


class DatabaseChange(ApiModel):
    target_table: str = Field(min_length=1)
    target_column: Optional[str]
    change_type: DatabaseChangeType
    description: str = Field(min_length=1)
    rationale: str = Field(min_length=1)

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
    frameworks: List[str]
    risk_level: RiskLevel
    risk_score: int = Field(ge=0, le=100)
    impact_areas: List[str]
    evidences: List[Dict[str, Any]]
    recommendations: List[str]


class RequirementGeneralAnalysisResponse(GeneralAnalysisResponse):
    database_relevance: DatabaseRelevance
    database_relevance_reason: str = Field(min_length=1)


class ProjectAnalysisResponse(GeneralAnalysisResponse):
    """구조 분석과 요구사항 분석 모두에 적용되는 최종 API 응답 형식."""
    database_impact: DatabaseImpact


class ModelHealthResponse(ApiModel):
    status: str
    model: str
    available: bool
