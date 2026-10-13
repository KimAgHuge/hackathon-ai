from enum import Enum
from typing import Dict, List, Optional

from pydantic import Field

from app.models.base import ApiModel


class TechnologyCategory(str, Enum):
    FRONTEND = "FRONTEND"
    BACKEND = "BACKEND"
    FULLSTACK = "FULLSTACK"
    MOBILE = "MOBILE"
    DESKTOP = "DESKTOP"
    SERVER_VIEW = "SERVER_VIEW"
    BUILD = "BUILD"
    STATE_MANAGEMENT = "STATE_MANAGEMENT"
    DATABASE = "DATABASE"
    MESSAGING = "MESSAGING"
    SECURITY = "SECURITY"
    OBSERVABILITY = "OBSERVABILITY"
    DATA = "DATA"
    AI_ML = "AI_ML"
    ORM = "ORM"
    CMS = "CMS"
    TEST = "TEST"
    UI = "UI"
    RUNTIME = "RUNTIME"
    LIBRARY = "LIBRARY"
    INFRASTRUCTURE = "INFRASTRUCTURE"
    UNKNOWN = "UNKNOWN"


class DetectionEvidence(ApiModel):
    file: str
    evidence_type: str
    value: str
    line: Optional[int] = None


class TechnologyFact(ApiModel):
    name: str
    category: TechnologyCategory
    ecosystem: str
    declared_version: Optional[str] = None
    resolved_version: Optional[str] = None
    confidence: float = Field(ge=0, le=1)
    evidences: List[DetectionEvidence] = Field(default_factory=list)


class ModuleFact(ApiModel):
    path: str
    ecosystems: List[str] = Field(default_factory=list)
    languages: List[str] = Field(default_factory=list)
    build_tools: List[str] = Field(default_factory=list)
    manifests: List[str] = Field(default_factory=list)
    technologies: List[TechnologyFact] = Field(default_factory=list)
    dependency_count: int = Field(default=0, ge=0)
    unclassified_dependencies: List[str] = Field(default_factory=list)


class RepositoryTechnologyReport(ApiModel):
    root_name: str
    modules: List[ModuleFact]
    languages: List[str]
    build_tools: List[str]
    technologies: List[TechnologyFact]
    scanned_file_count: int = Field(ge=0)
    warnings: List[str] = Field(default_factory=list)

    def to_source_summary(self) -> Dict:
        """탐지 보고서를 Backend·AI 분석 입력에 쓰는 간단한 sourceSummary로 변환한다."""
        framework_categories = {
            TechnologyCategory.FRONTEND,
            TechnologyCategory.BACKEND,
            TechnologyCategory.FULLSTACK,
            TechnologyCategory.MOBILE,
            TechnologyCategory.DESKTOP,
            TechnologyCategory.SERVER_VIEW,
            TechnologyCategory.DATA,
            TechnologyCategory.AI_ML,
            TechnologyCategory.ORM,
            TechnologyCategory.CMS,
        }
        return {
            "frameworks": [
                technology.model_dump(mode="json", by_alias=True)
                for technology in self.technologies
                if technology.category in framework_categories
            ],
            "technologies": [
                technology.model_dump(mode="json", by_alias=True)
                for technology in self.technologies
            ],
            "languages": self.languages,
            "buildTools": self.build_tools,
            "components": [
                {
                    "path": module.path,
                    "ecosystems": module.ecosystems,
                    "languages": module.languages,
                    "buildTools": module.build_tools,
                    "technologies": [
                        technology.model_dump(mode="json", by_alias=True)
                        for technology in module.technologies
                    ],
                }
                for module in self.modules
            ],
            "detectionWarnings": self.warnings,
        }
