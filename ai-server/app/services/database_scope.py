"""업무 단어 목록 없이 명시한 대상·소스 참조·외래키로 상세 검토 범위를 정한다."""

import json
import re
from collections import Counter

from app.services.source_context import explicit_requirement_text, source_context, supported_target_tables


def table_name(table):
    return f"{table.get('schema')}.{table.get('name')}"


def database_review_scope(request, plan=None):
    """전체 메타데이터는 보존하고 검증 가능한 대상이 있을 때만 모델 상세 입력을 좁힌다."""
    tables = [table for table in request.get("databaseSummary", {}).get("tables", []) if isinstance(table, dict)]
    known = {table_name(table): table for table in tables}
    requirement = request.get("sourceSummary", {}).get("changeRequirement", {})
    explicit = explicit_requirement_text(requirement)
    direct = {name for name in known if re.search(r"(?<![A-Za-z0-9_.$])" + re.escape(name) + r"(?![A-Za-z0-9_.$])", explicit)}
    selected = set(direct)
    reason = "사용자가 직접 적은 스키마 포함 테이블명을 기준으로 검토합니다."
    if not plan:
        selected.clear()
    if plan:
        if plan.get("requiresFullReview", True):
            selected.clear()
        else:
            targets = set(plan.get("targetTables", []))
            evidence_files = set(plan.get("evidenceFiles", []))
            supported = supported_target_tables(request)
            references = source_context(request)["tableReferences"]
            backed = {f"{item.get('schema')}.{item.get('table')}" for item in references
                      if item.get("file") in evidence_files and not item.get("tentative") and not item.get("ambiguous")}
            # 테이블 이름만 맞거나 예시·테스트 파일만 인용한 계획은 채택하지 않는다.
            if not targets or not targets <= known.keys() or not targets <= direct | (supported & backed):
                selected.clear()
            else:
                selected.update(targets)
                reason = "일반 분석의 우선 대상과 실제 소스 테이블 참조를 대조했습니다. 정적 연결은 실행 증거가 아닙니다."
    verified = bool(selected)
    if not selected:
        selected = set(known)
        reason = "대상 연결 근거가 부족하거나 전체 검토가 필요해 수집한 전체 테이블을 검토합니다."
    else:
        # 직접 대상의 들어오는·나가는 외래키 이웃은 함께 읽는다. 연쇄 전체를 추정하지 않는다.
        seeds = set(selected)
        for name, table in known.items():
            for key in table.get("foreignKeys", []):
                if not isinstance(key, dict):
                    continue
                schema = key.get("referencedSchema")
                target = f"{schema}.{key.get('referencedTable')}" if schema else None
                if target is None:
                    matches = [candidate for candidate, value in known.items() if value.get("name") == key.get("referencedTable")]
                else:
                    matches = [target] if target in known else []
                if name in seeds:
                    selected.update(matches)
                if seeds.intersection(matches):
                    selected.add(name)
    chosen = [table for table in tables if table_name(table) in selected]
    return chosen, {"mode": "FULL" if selected == set(known) else "FOCUSED", "reason": reason,
                    "selectionVerified": verified,
                    "collectedTables": len(tables), "reviewedTables": sorted(selected),
                    "omittedTables": sorted(set(known) - selected),
                    "notice": "명시한 상세 검토 범위의 판단입니다. 생략된 테이블과 동적 호출의 영향이 없음을 증명하지 않습니다."}


def metadata_checks(tables):
    """존재·키·인덱스의 객관적 목록만 만든다. 변경 필요성과 성능 우열은 판단하지 않는다."""
    checked = []
    for table in tables:
        columns = table.get("columns")
        names = [column.get("name") for column in columns if isinstance(column, dict) and isinstance(column.get("name"), str)] if isinstance(columns, list) else []
        primary = table.get("primaryKey", [])
        checked.append({"table": table_name(table), "columnMetadataAvailable": isinstance(columns, list), "columnCount": len(names),
                        "duplicateColumnNames": sorted(name for name, count in Counter(names).items() if count > 1),
                        "primaryKeyColumnsPresent": all(name in names for name in primary) if isinstance(columns, list) and isinstance(primary, list) else None,
                        "foreignKeyCount": len(table.get("foreignKeys", [])) if isinstance(table.get("foreignKeys", []), list) else None,
                        "indexCount": len(table.get("indexes", [])) if isinstance(table.get("indexes", []), list) else None})
    return checked


def database_input(request, tables):
    """운영 단독 DB 호출과 학습 메시지에서 같은 입력 형식을 사용한다."""
    return {"changeRequirement": request["sourceSummary"]["changeRequirement"],
            "databaseType": request["databaseSummary"].get("type"), "tables": tables,
            "promptCoverage": request["sourceSummary"].get("promptCoverage"), "sourceContext": database_source_context(request, tables),
            "reviewScope": request["sourceSummary"].get("databaseReviewScope"), "metadataChecks": metadata_checks(tables)}


def database_source_context(request, tables):
    """상세 테이블과 무관한 참조를 반복 전달하지 않는다. 원본 수집 자료는 수정하지 않는다."""
    names = {table_name(table) for table in tables}
    relations = [item for item in request.get("relations", []) if f"{item.get('schema')}.{item.get('table')}" in names]
    context = source_context({**request, "relations": relations})
    context["tableReferenceScope"] = sorted(names)
    context["omittedTableReferenceCount"] = len(request.get("relations", [])) - len(relations)
    return context


def review_groups(reviews, max_chars):
    """작은 결과를 고정 건수로 나누지 않고 입력 예산에 맞춰 묶는다."""
    groups, group, size = [], [], 0
    for review in reviews:
        length = len(json.dumps(review, ensure_ascii=False, separators=(",", ":")))
        if group and size + length > max_chars:
            groups.append(group)
            group, size = [], 0
        group.append(review)
        size += length
    if group:
        groups.append(group)
    # 각 결과가 예산을 넘으면 두 개씩 비교해 종합 단계가 끝나도록 한다.
    if len(groups) == len(reviews) and len(reviews) > 1:
        return [reviews[index:index + 2] for index in range(0, len(reviews), 2)]
    return groups
