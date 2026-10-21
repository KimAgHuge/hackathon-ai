"""최종 설명에 필요한 DB 사실을 모은다. 업무 단어를 테이블에 대응시키거나 실제 데이터를 읽지 않는다."""

import json
from collections import Counter
from copy import deepcopy


def reconciliation_facts(request, impact, source, max_chars=12000, max_tables=12):
    """변경·관찰 대상의 수집 구조만 전달하고 생략한 항목과 미확인 경계를 표시한다."""
    known = {f"{table.get('schema')}.{table.get('name')}": table
             for table in request["databaseSummary"].get("tables", []) if isinstance(table, dict)}
    targets = list(dict.fromkeys([change["targetTable"] for change in impact["proposedChanges"]] + impact["observedTables"]))
    selected = [name for name in targets if name in known]
    references = Counter(f"{item.get('schema')}.{item.get('table')}" for item in source["tableReferences"])
    tables = []
    for name in selected[:max_tables]:
        original = known[name]
        available = isinstance(original.get("columns"), list)
        columns = [{key: column[key] for key in ("name", "type", "nullable") if key in column}
                   for column in original.get("columns", []) if isinstance(column, dict) and isinstance(column.get("name"), str)] if available else []
        table = {"table": name, "columnsAvailable": available, "collectedColumnCount": len(columns) if available else None,
                 "columns": columns, "allCollectedColumnsIncluded": available,
                 "primaryKey": deepcopy(original.get("primaryKey")),
                 "foreignKeys": deepcopy(original.get("foreignKeys")) if isinstance(original.get("foreignKeys"), list) else [],
                 "indexes": deepcopy(original.get("indexes")) if isinstance(original.get("indexes"), list) else [],
                 "includedSourceTableReferences": references[name]}
        table["collectedForeignKeyCount"] = len(table["foreignKeys"]) if isinstance(original.get("foreignKeys"), list) else None
        table["collectedIndexCount"] = len(table["indexes"]) if isinstance(original.get("indexes"), list) else None
        tables.append(table)
    result = {"tables": tables, "requestedTables": targets, "omittedTables": selected[max_tables:],
              "targetsNotInCollectedMetadata": [name for name in targets if name not in known],
              "databaseCoverage": {"truncated": bool(request["databaseSummary"].get("truncated", False)),
                                   "warnings": deepcopy(request["databaseSummary"].get("warnings", []))},
              "sourceCoverage": deepcopy(source["coverage"]),
              "rowValuesVerified": False, "runtimeUsageVerified": False,
              "limitations": ["DB 구조의 관찰과 소스 연결·실행 경로의 확인은 별개입니다.",
                              "수집한 메타데이터만으로 JSON 내부 값·기존 행의 실제 상태·성능을 확인한 것은 아닙니다.",
                              "수집·입력 한도가 있으면 구조가 없다고 확정할 수 없습니다.",
                              "기존 행의 값을 채우는 작업과 스키마 변경·배포 작업은 별개입니다."]}
    # 큰 인덱스·관계·컬럼 목록을 생략해도 원래 수와 생략 범위를 남긴다.
    while len(json.dumps(result, ensure_ascii=False, separators=(",", ":"))) > max_chars and tables:
        table = max(tables, key=lambda value: len(json.dumps(value, ensure_ascii=False)))
        key = next((key for key in ("indexes", "foreignKeys", "columns") if table[key]), None)
        if key is None:
            result["omittedTables"].append(table["table"])
            tables.remove(table)
        else:
            table[key].pop()
            if key == "columns":
                table["allCollectedColumnsIncluded"] = False
    return result


def validate_database_facts_review(facts, reviews):
    """표현의 의미 전체가 아니라 명시한 구조 확인값의 누락·날조·중복을 검사한다."""
    expected = {table["table"]: table for table in facts["tables"]}
    if len(reviews) != len(expected) or {review.table for review in reviews} != expected.keys():
        raise ValueError("최종 정리의 DB 구조 확인값이 전달한 사실과 다릅니다.")
    for review in reviews:
        table = expected[review.table]
        names = [column["name"] for column in table["columns"]]
        if (review.columns_available != table["columnsAvailable"]
                or review.collected_column_count != table["collectedColumnCount"]
                or Counter(review.included_column_names) != Counter(names)
                or review.all_collected_columns_included != table["allCollectedColumnsIncluded"]):
            raise ValueError("최종 정리의 DB 구조 확인값이 전달한 사실과 다릅니다.")
