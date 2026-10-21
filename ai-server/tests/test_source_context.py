import copy
import json

from app.services.source_context import explicit_requirement_text, general_overview, source_context, supported_target_tables


def example_request():
    return {
        "sourceSummary": {"components": [{"path": "db/seed.py", "type": "MIGRATION"}],
                          "sourceConnections": {
                              "links": [{"file": "web/Page.jsx", "line": 3, "targetFile": "web/api.ts", "targetLine": 1},
                                        {"file": "tests/test_api.py", "targetFile": "app/store.py"}],
                              "routes": [{"file": "app/routes.py", "line": 4, "route": "/records"}],
                              "unresolvedReferences": [{"file": "web/Page.jsx", "line": 5, "symbol": "Store",
                                                        "reason": "AMBIGUOUS", "candidates": ["web/Store.js", "web/Store.py"]}],
                              "unsupportedLanguages": ["Ruby"], "inspectedLanguages": ["Python", "JavaScript"]}},
        "databaseSummary": {"tables": [{"schema": "example", "name": "table_example", "columns": [{"name": "id"}]}]},
        "relations": [{"file": "app/store.py", "line": 6, "schema": "example", "table": "table_example"},
                      {"file": "db/seed.py", "line": 8, "schema": "example", "table": "legacy"},
                      {"file": "mocks/store.py", "schema": "example", "table": "mocked"},
                      {"file": "app/unknown.py", "schema": "example", "table": "tentative", "tentative": True},
                      {"file": "app/ambiguous.py", "schema": "example", "table": "ambiguous", "ambiguous": True}]}


def test_context_preserves_ambiguity_and_supported_scope_without_mutating_input():
    request = example_request()
    before = copy.deepcopy(request)
    context = source_context(request)
    assert context["unresolvedReferences"][0]["reason"] == "AMBIGUOUS"
    assert context["unsupportedLanguages"] == ["Ruby"]
    assert any(item.get("ambiguous") for item in context["tableReferences"])
    assert len(context["links"]) == 1
    assert supported_target_tables(request) == {"example.table_example"}
    overview = general_overview(request)
    assert "sourceConnections" not in overview["sourceSummary"]
    assert "columns" not in overview["databaseSummary"]["tables"][0]
    assert overview["databaseSummary"]["detailLevel"] == "TABLE_OVERVIEW_ONLY"
    assert request == before


def test_context_limits_report_omitted_connections():
    request = example_request()
    request["sourceSummary"]["sourceConnections"]["links"] = [
        {"file": f"web/Page{index}.jsx", "line": 1, "targetFile": "app/store.py", "symbol": "x" * 300}
        for index in range(100)]
    context = source_context(request)
    assert len(json.dumps(context, ensure_ascii=False)) < 16_500
    assert context["coverage"]["collected"]["links"] == 100
    assert context["coverage"]["included"]["links"] < 60
    assert context["coverage"]["truncated"]


def test_previous_model_question_is_not_an_explicit_human_target_confirmation():
    text = explicit_requirement_text({"title": "완료일 추가", "clarifications": [
        {"kind": "AI_QUESTION", "question": "example.unconfirmed가 대상인가요?", "answer": "아직 모릅니다."},
        {"kind": "USER_QUESTION", "question": "example.confirmed에 추가해 주세요."}]})
    assert "example.unconfirmed" not in text
    assert "example.confirmed" in text
