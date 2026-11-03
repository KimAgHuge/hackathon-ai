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


def constrain_database_facts(schema, facts):
    """내부 구조 확인 목록을 실제 입력의 고정값으로 제한하고 원본 스키마는 보존한다."""
    scoped = deepcopy(schema)
    properties = scoped.get("properties")
    if facts is None or not isinstance(properties, dict) or "databaseFactsReview" not in properties:
        return scoped
    expected = [{
        "table": table["table"],
        "columnsAvailable": table["columnsAvailable"],
        "collectedColumnCount": table["collectedColumnCount"],
        "includedColumnNames": [column["name"] for column in table["columns"]],
        "allCollectedColumnsIncluded": table["allCollectedColumnsIncluded"],
        "indexesAvailable": table["indexesAvailable"],
        "collectedIndexCount": table["collectedIndexCount"],
        "includedIndexes": [{key: index.get(key) for key in ("name", "columns", "unique")}
                            for index in table["indexes"]],
        "allCollectedIndexesIncluded": table["allCollectedIndexesIncluded"],
    } for table in facts["tables"]]
    properties["databaseFactsReview"] = {"type": "array", "const": deepcopy(expected)}
    return scoped


def constrain_evidence(schema, allowed_references):
    """파일별 줄 번호를 묶어 다른 파일의 줄을 섞거나 가짜 경로를 생성하지 못하게 한다."""
    scoped = deepcopy(schema)
    evidence = scoped.get("properties", {}).get("evidences")
    if allowed_references is None or not isinstance(evidence, dict):
        return scoped
    by_file = {}
    for file, line in allowed_references:
        if isinstance(file, str) and (line is None or type(line) is int and line > 0):
            lines = by_file.setdefault(file, set())
            if line is not None:
                lines.add(line)
    choices = [{
        "type": "object",
        "properties": {"file": {"type": "string", "const": file},
                       "line": {"type": ["integer", "null"], "enum": [None, *sorted(lines)]}},
        "required": ["file"],
        "additionalProperties": True,
    } for file, lines in sorted(by_file.items())]

    def restrict_array(branch):
        if branch.get("type") == "array":
            if choices:
                branch["items"] = {"anyOf": deepcopy(choices)}
            else:
                branch["maxItems"] = 0
        for child in branch.get("anyOf", []):
            restrict_array(child)

    restrict_array(evidence)
    evidence["description"] = "전달된 파일과 줄 조합에서만 근거를 선택합니다. 줄을 특정하지 못하면 line을 생략하거나 null로 두고, 적합한 근거가 없으면 빈 배열로 둡니다."
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
            "FINAL_DB_NARRATIVE_MISMATCH": {"databaseNarrative", "conditionalActionsReview"},
            "DB_CONDITIONAL_ACTIONS_MISMATCH": {"explanation", "conditionalPaths", "openQuestions", "conditionalActions"},
            "DB_CONFIRMED_ACTIONS_MISMATCH": {"status", "explanation", "proposedChanges", "conditionalPaths", "openQuestions", "confirmedActions"},
            "DB_SYNTHESIS_TARGET_OUTSIDE_SCOPE": {"status", "explanation", "proposedChanges", "conditionalPaths", "openQuestions", "conditionalActions"},
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
    if "conditionalActions" in properties and fields & {"status", "proposedChanges", "conditionalPaths"}:
        fields.add("conditionalActions")
    if "confirmedActions" in properties and fields & {"status", "proposedChanges"}:
        fields.add("confirmedActions")
    if "databaseNarrative" in properties and fields & {"status", "explanation", "proposedChanges", "conditionalPaths", "openQuestions"}:
        fields = {"databaseNarrative", "conditionalActionsReview"}
    if code == "FINAL_DB_FACTS_MISMATCH":
        # 사실 확인값만 고치고 같은 근거를 잘못 설명한 문단은 남기지 않도록 함께 검토한다.
        if "databaseNarrative" in properties:
            fields |= {"databaseNarrative", "summary", "plainLanguageSummary", "riskAssessment", "impactAreas", "recommendations"}
        elif "conditionalActions" in properties:
            fields |= {"explanation", "conditionalPaths", "openQuestions", "conditionalActions"}
    if "databaseNarrative" in fields:
        fields |= {"summary", "plainLanguageSummary", "riskAssessment", "impactAreas", "recommendations"}
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
