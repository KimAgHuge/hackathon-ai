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


class ProjectAnalysisResponse(ApiModel):
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


class ModelHealthResponse(ApiModel):
    status: str
    model: str
    available: bool
