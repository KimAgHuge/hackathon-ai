"""수집한 DB 구조를 복사해 전달하며 확인하지 않은 값과 생략한 범위를 구분한다."""

import json
import re
from copy import deepcopy


def _size(value):
    return len(json.dumps(value, ensure_ascii=False, separators=(",", ":")))


def _table_name(table):
    name, schema = table.get("name"), table.get("schema")
    if not isinstance(name, str):
        return None
    return f"{schema}.{name}" if isinstance(schema, str) and schema else name


def _json_type(value):
    return isinstance(value, str) and re.fullmatch(
        r"\s*(?:[A-Za-z_][A-Za-z0-9_]*\s*\.\s*)?jsonb?\s*(?:\([^)]*\))?\s*", value, re.IGNORECASE
    ) is not None


def _index(index):
    columns = index.get("columns")
    return {
        "name": deepcopy(index.get("name")) if isinstance(index.get("name"), str) else None,
        "columns": deepcopy(columns) if isinstance(columns, list) and all(isinstance(name, str) for name in columns) else None,
        "unique": index.get("unique") if type(index.get("unique")) is bool else None,
    }


def _table_facts(original, include_columns, include_relationships):
    raw_columns, raw_indexes = original.get("columns"), original.get("indexes")
    raw_foreign_keys, raw_primary_key = original.get("foreignKeys"), original.get("primaryKey")
    columns_available = isinstance(raw_columns, list)
    columns = [{key: deepcopy(column[key]) for key in ("name", "type", "nullable") if key in column}
               for column in raw_columns if isinstance(column, dict) and isinstance(column.get("name"), str)] if columns_available else []
    json_columns = [{"column": column["name"], "internalKeys": "UNVERIFIED", "rowValues": "UNVERIFIED"}
                    for column in columns if _json_type(column.get("type"))]
    result = {
        "table": _table_name(original),
        "columnsAvailable": columns_available,
        "collectedColumnCount": len(raw_columns) if columns_available else None,
        "columns": columns if include_columns else [],
        "indexesAvailable": isinstance(raw_indexes, list),
        "collectedIndexCount": len(raw_indexes) if isinstance(raw_indexes, list) else None,
        "indexes": [_index(index) for index in raw_indexes if isinstance(index, dict)] if isinstance(raw_indexes, list) else [],
        "foreignKeysAvailable": isinstance(raw_foreign_keys, list),
        "collectedForeignKeyCount": len(raw_foreign_keys) if isinstance(raw_foreign_keys, list) else None,
        "foreignKeys": [deepcopy(key) for key in raw_foreign_keys if isinstance(key, dict)] if isinstance(raw_foreign_keys, list) else [],
        "primaryKeyAvailable": isinstance(raw_primary_key, list),
        "collectedPrimaryKeyColumnCount": len(raw_primary_key) if isinstance(raw_primary_key, list) else None,
        "primaryKey": [deepcopy(name) for name in raw_primary_key if isinstance(name, str)] if isinstance(raw_primary_key, list) else None,
        "jsonColumns": json_columns,
        "collectedJsonColumnCount": len(json_columns) if columns_available else None,
        "jsonInternalKeysVerified": False,
        "rowValuesVerified": False,
    }
    if "includedSourceTableReferences" in original:
        result["includedSourceTableReferences"] = deepcopy(original["includedSourceTableReferences"])
    if not include_relationships:
        for key in ("foreignKeysAvailable", "collectedForeignKeyCount", "foreignKeys",
                    "primaryKeyAvailable", "collectedPrimaryKeyColumnCount", "primaryKey"):
            result.pop(key)
    return result


def _update_coverage(result):
    truncated = bool(result["omittedTables"] or result["unidentifiedTableCount"] or result.get("relationshipDetailsOmitted"))
    for table in result["tables"]:
        for values, available, count, included, omitted in (
            ("columns", "columnsAvailable", "collectedColumnCount", "allCollectedColumnsIncluded", "omittedColumnCount"),
            ("indexes", "indexesAvailable", "collectedIndexCount", "allCollectedIndexesIncluded", "omittedIndexCount"),
            ("foreignKeys", "foreignKeysAvailable", "collectedForeignKeyCount", "allCollectedForeignKeysIncluded", "omittedForeignKeyCount"),
            ("primaryKey", "primaryKeyAvailable", "collectedPrimaryKeyColumnCount", "allCollectedPrimaryKeyColumnsIncluded", "omittedPrimaryKeyColumnCount"),
            ("jsonColumns", "columnsAvailable", "collectedJsonColumnCount", "allCollectedJsonColumnsIncluded", "omittedJsonColumnCount"),
        ):
            if values not in table:
                continue
            total = table[count]
            length = len(table[values] or [])
            table[included] = table[available] and length == total
            table[omitted] = total - length if total is not None else None
            truncated = truncated or total is not None and length < total
    result["truncated"] = truncated


def build_database_facts(tables, max_chars=12000, max_tables=12, *, include_columns=True,
                         include_relationships=True, context=None):
    """수집 건수는 유지하고 목록을 줄인다. 최소 상태도 예산을 넘으면 이를 명시한다."""
    if type(max_chars) is not int or max_chars < 0 or type(max_tables) is not int or max_tables < 0:
        raise ValueError("DB 사실의 문자 예산과 테이블 수는 음수가 아닌 정수여야 합니다.")
    available = isinstance(tables, list)
    originals = [table for table in tables if isinstance(table, dict) and _table_name(table) is not None] if available else []
    result = deepcopy(context) if isinstance(context, dict) else {}
    prior_omissions = result.get("omittedTables", [])
    result.update({
        "tablesAvailable": available,
        "collectedTableCount": len(tables) if available else None,
        "unidentifiedTableCount": len(tables) - len(originals) if available else None,
        "tables": [_table_facts(table, include_columns, include_relationships) for table in originals[:max_tables]],
        "omittedTables": [*prior_omissions, *[_table_name(table) for table in originals[max_tables:]]],
        "rowValuesVerified": False,
        "jsonInternalKeysVerified": False,
        "runtimeUsageVerified": False,
        "performanceVerified": False,
        "budgetExceeded": False,
    })
    if not include_relationships:
        result["relationshipDetailsOmitted"] = True
    _update_coverage(result)
    # 컬럼과 관계의 원래 건수는 남기고 목록만 줄인다. 인덱스의 일부 속성을 잘라 다른 사실로 바꾸지 않는다.
    while _size(result) > max_chars and result["tables"]:
        candidates = [(table, key) for table in result["tables"]
                      for key in ("columns", "foreignKeys", "indexes", "jsonColumns", "primaryKey") if table.get(key)]
        if candidates:
            table, key = max(candidates, key=lambda item: _size(item[0][item[1]]))
            table[key].pop()
        else:
            result["omittedTables"].insert(0, result["tables"].pop()["table"])
        _update_coverage(result)
    if _size(result) > max_chars:
        result["budgetExceeded"] = True
    return result
