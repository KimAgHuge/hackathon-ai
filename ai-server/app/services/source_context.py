"""분석·학습에서 같은 소스 연결 근거와 범위 표시를 사용한다. 업무 이름을 테이블명으로 번역하지 않는다."""

import json
import re


def _runtime_candidate(file, types):
    return (isinstance(file, str) and types.get(file) not in {"TEST", "MIGRATION"}
            and not re.search(r"(^|/)(tests?|migrations?|mocks?|fixtures?)(/|$)", file, re.I)
            and not re.search(r"(?:Test|Tests|Spec)\.[^.]+$|\.(?:test|spec)\.[^.]+$", file))


def source_context(request):
    """허용된 파일·줄·연결 식별자만 전달하고 잘린 근거 수를 명시한다."""
    source = request.get("sourceSummary", {})
    types = {item.get("path"): item.get("type") for item in source.get("components", []) if isinstance(item, dict)}
    graph = source.get("sourceConnections", {})
    if not isinstance(graph, dict):
        graph = {}
    references = [{key: item[key] for key in ("file", "line", "schema", "table", "kind", "tentative", "ambiguous") if key in item}
                  for item in request.get("relations", []) if isinstance(item, dict)
                  and _runtime_candidate(item.get("file"), types)]
    linked_files = {item["file"] for item in references}
    links = [item for item in graph.get("links", []) if isinstance(item, dict)
             and _runtime_candidate(item.get("file"), types) and _runtime_candidate(item.get("targetFile"), types)]
    # 테이블 참조 파일로 이어지는 상위 참조를 우선하되 이것을 실제 실행 경로라고 부르지 않는다.
    for _ in range(3):
        linked_files.update(item["file"] for item in links if item.get("targetFile") in linked_files)
    links.sort(key=lambda item: (item.get("file") not in linked_files, item.get("file", ""), item.get("targetFile", "")))
    links = [{key: item[key] for key in ("file", "line", "targetFile", "targetLine", "symbol", "kind", "tentative") if key in item} for item in links]
    routes = [{key: item[key] for key in ("file", "line", "kind", "route") if key in item}
              for item in graph.get("routes", []) if isinstance(item, dict) and _runtime_candidate(item.get("file"), types)]
    unresolved = [{key: item[key] for key in ("file", "line", "symbol", "reason", "candidates") if key in item}
                  for item in graph.get("unresolvedReferences", []) if isinstance(item, dict)
                  and _runtime_candidate(item.get("file"), types)]
    totals = {"tableReferences": len(references), "links": len(links), "routes": len(routes), "unresolvedReferences": len(unresolved)}
    result = {"version": 1, "tableReferences": references[:80], "links": links[:60], "routes": routes[:40],
              "unresolvedReferences": unresolved[:20],
              "coverage": {"collected": totals, "upstreamTruncated": bool(graph.get("truncated", False))},
              "inspectedLanguages": graph.get("inspectedLanguages", []), "unsupportedLanguages": graph.get("unsupportedLanguages", [])}
    return _finish_context(result)


def _finish_context(result):
    result["limitations"] = ["테이블 이름의 유사성만으로 업무 기능의 대응을 확정할 수 없습니다.",
                              "정적 참조·가져오기·주소 선언은 실제 호출 그래프가 아니며 별칭은 잠정 후보입니다."]
    while len(json.dumps(result, ensure_ascii=False)) > 16_000:
        key = next((key for key in ("links", "unresolvedReferences", "tableReferences", "routes") if result[key]), None)
        if key is None:
            break
        result[key].pop()
    result["coverage"]["included"] = {key: len(result[key]) for key in ("tableReferences", "links", "routes", "unresolvedReferences")}
    result["coverage"]["truncated"] = (result["coverage"]["upstreamTruncated"]
                                        or result["coverage"]["included"] != result["coverage"]["collected"])
    return result


def general_overview(request):
    # 소스 연결 함수를 쓰는 범위 모듈과 순환하지 않도록 호출할 때 가져온다.
    from app.services.database_scope import database_coverage

    payload = json.loads(json.dumps(request, ensure_ascii=False))
    payload.pop("thinkingEnabled", None)
    payload.pop("thinkingEffort", None)
    context = source_context(payload)
    payload["sourceSummary"].pop("sourceConnections", None)
    payload["sourceSummary"]["sourceContext"] = context
    payload["relations"] = context["tableReferences"]
    database = payload["databaseSummary"]
    database.update(database_coverage(database))
    database.pop("capturedAt", None)
    if isinstance(database.get("tables"), list):
        database["tables"] = [{key: table[key] for key in ("schema", "name", "type") if key in table}
                              for table in database["tables"] if isinstance(table, dict)]
        database["detailLevel"] = "TABLE_OVERVIEW_ONLY"
    return payload


def evidence_references(request, context=None):
    """모델에 전달한 소스 항목에서 파일과 줄의 허용 조합을 한 번만 계산한다."""
    source = request.get("sourceSummary", {})
    context = source_context(request) if context is None else context
    items = [*request.get("relations", []), *source.get("components", []),
             *context["tableReferences"], *context["links"], *context["routes"],
             *[{"file": item.get("targetFile"), "line": item.get("targetLine")} for item in context["links"]]]
    references = {(item.get("file", item.get("path")), item.get("line")) for item in items
                  if isinstance(item, dict) and isinstance(item.get("file", item.get("path")), str)
                  and (item.get("line") is None or type(item.get("line")) is int)}
    # 줄 번호를 특정하지 않은 파일 단위 근거는 기존 검사와 같이 허용한다.
    return frozenset({(file, line) for file, line in references if line is not None and line > 0}
                     | {(file, None) for file, _ in references})


def supported_target_tables(request):
    return {f"{item['schema']}.{item['table']}" for item in source_context(request)["tableReferences"]
            if isinstance(item.get("schema"), str) and isinstance(item.get("table"), str)
            and not item.get("tentative", False) and not item.get("ambiguous", False)}


def explicit_requirement_text(requirement):
    values = [requirement.get(key, "") for key in ("title", "refinedContent", "acceptanceCriteria", "latestFollowUpQuestion")]
    for item in requirement.get("clarifications", []):
        if isinstance(item, dict):
            values.append(item.get("answer", ""))
            if item.get("kind") == "USER_QUESTION":
                values.append(item.get("question", ""))
    return json.dumps(values, ensure_ascii=False)
