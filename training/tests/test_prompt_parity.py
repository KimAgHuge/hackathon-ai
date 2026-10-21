"""학습 입력이 현재 서버의 일반 단계 범위를 따르고 원본을 보존하는지 확인한다."""

import ast
import copy
import hashlib
import json
import sys
import unittest
from pathlib import Path
from typing import Any
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from dataset_contract import AI_ROOT, digest, general_overview, messages_for, prompt_contract
from app.models.analysis import ProjectAnalysisRequest
from app.services.source_context import general_overview as runtime_general_overview, source_context
from app.services.database_scope import database_input, database_review_scope


def runtime_overview():
    """통신 의존성을 불러오지 않고 실제 서버의 순수 변환 함수만 읽는다."""
    source = (AI_ROOT / "app/services/llm_service.py").read_text()
    service = next(node for node in ast.parse(source).body
                   if isinstance(node, ast.ClassDef) and node.name == "LlmService")
    method = next(node for node in service.body
                  if isinstance(node, ast.FunctionDef) and node.name == "_general_overview")
    method.decorator_list = []
    namespace = {"ProjectAnalysisRequest": ProjectAnalysisRequest, "Any": Any,
                 "general_overview": runtime_general_overview}
    exec(compile(ast.Module(body=[method], type_ignores=[]), "<runtime-overview>", "exec"), namespace)
    return namespace["_general_overview"]


def request_fixture():
    """실제 승인 자료를 포함하지 않는 소스·DB 입력을 만든다."""
    return {
        "projectId": "00000000-0000-0000-0000-000000000000",
        "sourceSummary": {
            "changeRequirement": {"title": "화면 표시 개선", "clarifications": [{"answer": "목록을 유지합니다."}]},
            "components": [{"path": "src/list.py", "line": 7, "details": {"observed": True}}],
            "promptCoverage": {"omitted": 0},
            "sourceConnections": {
                "links": [{"file": "src/handler.py", "line": 3, "targetFile": "src/list.py", "symbol": "load"}],
                "routes": [{"file": "src/handler.py", "line": 2, "route": "/items"}],
                "unresolvedReferences": [], "truncated": False,
            },
        },
        "databaseSummary": {
            "type": "POSTGRESQL", "capturedAt": "2026-10-21T10:00:00+09:00",
            "collectionCoverage": {"complete": True},
            "tables": [{
                "schema": "public", "name": "items", "type": "TABLE",
                "columns": [{"name": "id", "nullable": False}], "primaryKey": ["id"],
                "foreignKeys": [], "indexes": [{"name": "items_pk", "unique": True}],
            }],
        },
        "relations": [
            {"file": "src/list.py", "line": 7, "schema": "public", "table": "items", "extra": "입력 전용 값"},
            {"file": "tests/list_test.py", "line": 5, "schema": "public", "table": "items"},
        ],
    }


class PromptParityTests(unittest.TestCase):
    """입력 변환의 운영 일치·비변형·계약 추적을 검사한다."""

    def test_general_overview_matches_actual_runtime(self):
        request = request_fixture()
        expected = runtime_overview()(ProjectAnalysisRequest.model_validate(request))
        self.assertEqual(general_overview(request), expected)
        self.assertEqual(expected["databaseSummary"]["tables"], [
            {"schema": "public", "name": "items", "type": "TABLE"},
        ])
        self.assertEqual(expected["databaseSummary"]["detailLevel"], "TABLE_OVERVIEW_ONLY")
        self.assertNotIn("capturedAt", expected["databaseSummary"])

    def test_original_is_preserved_and_context_matches_shared_runtime(self):
        request = request_fixture()
        before = copy.deepcopy(request)
        overview = general_overview(request)
        self.assertEqual(request, before)
        self.assertIs(general_overview, runtime_general_overview)
        self.assertEqual(overview, runtime_general_overview(request))
        context = source_context(before)
        self.assertEqual(overview["sourceSummary"]["sourceContext"], context)
        self.assertEqual(overview["relations"], context["tableReferences"])
        self.assertNotIn("sourceConnections", overview["sourceSummary"])
        for key in ("changeRequirement", "components", "promptCoverage"):
            self.assertEqual(overview["sourceSummary"][key], before["sourceSummary"][key])
        self.assertEqual(overview["databaseSummary"]["collectionCoverage"], {"complete": True})
        overview["sourceSummary"]["components"][0]["details"]["observed"] = False
        overview["relations"][0]["table"] = "변경"
        self.assertEqual(request, before)

    def test_missing_type_empty_or_nonlist_tables_match_runtime(self):
        transform = runtime_overview()
        for tables in ([{"schema": "public", "name": "items"}, None], [], None):
            with self.subTest(tables=tables):
                request = request_fixture()
                request["databaseSummary"]["tables"] = tables
                self.assertEqual(general_overview(request), transform(ProjectAnalysisRequest.model_validate(request)))

    def test_general_messages_use_overview_and_database_messages_keep_details(self):
        request = request_fixture()
        bundle, _ = prompt_contract()
        case = {"request": request, "answers": {"general": {}}}
        marker = "Analyze these project facts and return only the required JSON object:\n"
        general = messages_for(case, "general", bundle)
        payload = json.loads(general[1]["content"].split(marker, 1)[1])
        self.assertEqual(payload, general_overview(request))
        self.assertIn("Required JSON Schema", general[0]["content"])
        database = json.loads(messages_for(case, "database", bundle)[1]["content"])
        scoped = copy.deepcopy(request)
        selected, scope = database_review_scope(scoped)
        scoped["databaseSummary"]["tables"] = selected
        scoped["sourceSummary"]["databaseReviewScope"] = scope
        tables = [{key: table.get(key, [] if key not in {"schema", "name"} else None)
                   for key in ("schema", "name", "columns", "primaryKey", "foreignKeys", "indexes")}
                  for table in selected]
        self.assertEqual(database, database_input(scoped, tables))
        self.assertEqual(database["tables"][0]["columns"], request["databaseSummary"]["tables"][0]["columns"])
        self.assertEqual(database["tables"][0]["indexes"], request["databaseSummary"]["tables"][0]["indexes"])
        self.assertIn("capturedAt", request["databaseSummary"])

    def test_current_contract_version_and_shared_transform_files_are_tracked(self):
        bundle, contract_hash = prompt_contract()
        self.assertEqual(bundle["messageFormatVersion"], 3)
        names = ("source_context.py", "database_scope.py")
        expected = {name: hashlib.sha256((AI_ROOT / "app/services" / name).read_bytes()).hexdigest()
                    for name in names}
        self.assertEqual(bundle["inputTransformations"], expected)
        self.assertEqual(contract_hash, digest(bundle))
        original = Path.read_bytes
        for name in names:
            with self.subTest(name=name):
                with patch.object(Path, "read_bytes", new=lambda path: original(path) + (
                    "\n# 변환 변경\n".encode("utf-8") if path.name == name else b""
                )):
                    changed_bundle, changed_hash = prompt_contract()
                self.assertNotEqual(changed_hash, contract_hash)
                self.assertNotEqual(changed_bundle["inputTransformations"][name], bundle["inputTransformations"][name])


if __name__ == "__main__":
    unittest.main()
