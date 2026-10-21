"""응답 검증에서 지정한 최상위 항목만 교체한다. 수정 후에는 전체 계약을 다시 검사한다."""

from copy import deepcopy

from pydantic import ValidationError


def restrict_observed_tables(schema, tables):
    """이번 호출에서 상세 근거를 받은 테이블만 관찰 목록으로 생성하도록 제한한다."""
    scoped = deepcopy(schema)
    observed = scoped.get("properties", {}).get("observedTables")
    if tables is None or observed is None:
        return scoped
    observed["description"] = "이번 호출의 상세 검토 범위에서 실제 확인한 테이블만 작성합니다. 전체 이름 목록은 상세 관찰 근거가 아닙니다."
    if tables:
        observed["items"] = {**observed.get("items", {}), "enum": sorted(tables)}
    else:
        # 빈 enum 대신 빈 배열만 허용해 표준 JSON Schema의 유효성을 유지한다.
        observed["maxItems"] = 0
    return scoped


def repair_contract(response_type, error, code):
    schema = response_type.model_json_schema(by_alias=True)
    properties = schema.get("properties", {})
    fields = set()
    if isinstance(error, ValidationError):
        for issue in error.errors(include_input=False, include_url=False):
            path = issue.get("loc", ())
            if path and path[0] in properties:
                fields.add(path[0])
            elif path:
                return None
    if not fields:
        decision = {"status", "explanation", "proposedChanges", "conditionalPaths", "openQuestions"}
        fields = {
            "FINAL_EVIDENCE_NOT_OBSERVED": {"evidences"},
            "FINAL_DB_FACTS_MISMATCH": {"databaseFactsReview"},
            "GENERAL_EVIDENCE_NOT_OBSERVED": {"evidences"},
            "FINAL_DB_RELEVANCE_FLIP": {"databaseRelevance", "databaseRelevanceReason"},
            "RISK_FACTOR_RANGE": {"riskAssessment"},
            "PLAIN_SUMMARY_FORMAT": {"plainLanguageSummary"},
            "DB_NECESSITY_MISSING": {"proposedChanges"},
            "DB_DUPLICATE_ADDITION": {"proposedChanges"},
            "DB_UNKNOWN_OBSERVED_TABLE": {"observedTables"},
            "DB_OBSERVED_TABLE_OUTSIDE_SCOPE": {"observedTables"},
            "DB_KOREAN_SENTENCE": {"explanation", "proposedChanges", "conditionalPaths", "openQuestions"},
            **{value: decision for value in ("DB_ADD_EXISTING_COLUMN", "DB_CREATE_EXISTING_TABLE", "DB_TARGET_MAPPING_MISSING",
                                              "DB_RECOMMENDATION_ONLY", "DB_CHANGE_NEEDS_PROPOSAL", "DB_NOT_APPLICABLE",
                                              "DB_NEEDS_QUESTION", "DB_NEEDS_PATHS", "DB_UNCONFIRMED_CHANGE",
                                              "DB_UNKNOWN_CHANGE_TABLE", "DB_UNKNOWN_CHANGE_COLUMN", "DB_UNKNOWN_INDEX_COLUMN")},
        }.get(code, set())
    if not fields or not fields <= properties.keys() or fields == properties.keys():
        return None
    selected = sorted(fields)
    patch_schema = {"type": "object", "properties": {key: deepcopy(properties[key]) for key in selected},
                    "required": selected, "additionalProperties": False}
    if "$defs" in schema:
        patch_schema["$defs"] = deepcopy(schema["$defs"])
    return selected, patch_schema


def apply_repair(original, patch, fields):
    if not isinstance(patch, dict) or set(patch) != set(fields):
        raise ValueError("수정 응답은 지정된 항목만 모두 포함해야 합니다.")
    return {**deepcopy(original), **patch}
