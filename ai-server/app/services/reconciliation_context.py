"""최종 설명에 필요한 DB 사실을 모은다. 업무 단어를 테이블에 대응시키거나 실제 데이터를 읽지 않는다."""

from collections import Counter
from copy import deepcopy

from app.services.database_facts import build_database_facts
from app.services.database_scope import database_coverage


FACTS_ERROR_PREFIX = "최종 정리의 DB 구조 확인값이 전달한 사실과 다릅니다."


def reconciliation_facts(request, impact, source, max_chars=12000, max_tables=12, *, extra_targets=()):
    """변경·관찰 대상의 수집 구조만 전달하고 생략한 항목과 미확인 경계를 표시한다."""
    known = {f"{table.get('schema')}.{table.get('name')}": table
             for table in request["databaseSummary"].get("tables", []) if isinstance(table, dict)}
    targets = list(dict.fromkeys([change["targetTable"] for change in impact["proposedChanges"]]
                                + list(extra_targets) + impact["observedTables"]))
    selected = [name for name in targets if name in known]
    references = Counter(f"{item.get('schema')}.{item.get('table')}" for item in source["tableReferences"])
    context = {"requestedTables": targets,
              "targetsNotInCollectedMetadata": [name for name in targets if name not in known],
              "databaseCoverage": database_coverage(request["databaseSummary"]),
              "sourceCoverage": deepcopy(source["coverage"]),
              "limitations": ["DB 구조의 관찰과 소스 연결·실행 경로의 확인은 별개입니다.",
                              "수집한 메타데이터만으로 JSON 내부 값·기존 행의 실제 상태·성능을 확인한 것은 아닙니다.",
                              "수집·입력 한도가 있으면 구조가 없다고 확정할 수 없습니다.",
                              "기존 행의 값을 채우는 작업과 스키마 변경·배포 작업은 별개입니다."]}
    # 참조 건수도 같은 문자 예산에 넣기 위해 모델 입력용 사본에만 붙인다.
    originals = []
    for name in selected:
        table = deepcopy(known[name])
        table["includedSourceTableReferences"] = references[name]
        originals.append(table)
    return build_database_facts(originals, max_chars=max_chars, max_tables=max_tables, context=context)


def validate_database_facts_review(facts, reviews):
    """표현의 의미 전체가 아니라 명시한 구조 확인값의 누락·날조·중복을 검사한다."""
    expected = {table["table"]: table for table in facts["tables"]}
    if len(reviews) != len(expected) or {review.table for review in reviews} != expected.keys():
        raise ValueError(f"{FACTS_ERROR_PREFIX} 확인 필드: tables.")
    actual = {review.table: review for review in reviews}
    issues = []
    for number, table in enumerate(facts["tables"], 1):
        review = actual[table["table"]]
        names = [column["name"] for column in table["columns"]]
        checks = (
            ("columnsAvailable", review.columns_available == table["columnsAvailable"]),
            ("collectedColumnCount", review.collected_column_count == table["collectedColumnCount"]),
            ("includedColumnNames", Counter(review.included_column_names) == Counter(names)),
            ("allCollectedColumnsIncluded", review.all_collected_columns_included == table["allCollectedColumnsIncluded"]),
            ("indexesAvailable", review.indexes_available == table["indexesAvailable"]),
            ("collectedIndexCount", review.collected_index_count == table["collectedIndexCount"]),
            ("includedIndexes", _index_counts(review.included_indexes) == _index_counts(table["indexes"])),
            ("allCollectedIndexesIncluded", review.all_collected_indexes_included == table["allCollectedIndexesIncluded"]),
        )
        fields = [field for field, matches in checks if not matches]
        if fields:
            # 원본 이름과 모델 값 대신 입력 순서와 고정된 확인 항목만 교정 요청에 넣는다.
            issues.append(f"테이블 순서 {number}: {', '.join(fields)}")
    if issues:
        raise ValueError(f"{FACTS_ERROR_PREFIX} {'; '.join(issues)}.")


def _index_counts(indexes):
    """순서와 무관하게 인덱스의 이름·컬럼 순서·고유 여부와 중복을 대조한다."""
    return Counter((index.get("name"), tuple(index["columns"]) if index.get("columns") is not None else None,
                    index.get("unique")) if isinstance(index, dict)
                   else (index.name, tuple(index.columns) if index.columns is not None else None, index.unique)
                   for index in indexes)
