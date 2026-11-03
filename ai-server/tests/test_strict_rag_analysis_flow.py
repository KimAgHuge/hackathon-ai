import asyncio
import copy
import json
from types import SimpleNamespace

import pytest

from app.models.analysis import ProjectAnalysisRequest
from app.services.evidence_retriever import EvidenceRetriever
from app.services.llm_service import InvalidModelResponseError, LlmService, ModelUnavailableError
from tests.test_llm_service import REQUIREMENT_GENERAL_MODEL_JSON, _enrich_internal_fixture, settings


UNRELATED = "unrelated_sentinel"
TARGET = "sample.completion_records"
NEIGHBOR = "sample.owners"
RELATED = {TARGET, NEIGHBOR, "sample.completion_notes"}
TARGET_FILE = "src/CompletionStore.py"


class MemoryStore:
    """검색 색인을 파일 없이 보관해 실제 검색과 분석 흐름을 함께 검사한다."""

    def __init__(self):
        self.items = {}

    def has(self, project_id, key):
        return bool(self.items.get((project_id, key)))

    def load(self, project_id, key):
        return copy.deepcopy(self.items.get((project_id, key), []))

    def save(self, project_id, key, chunks, embeddings, model):
        self.items = {stored: value for stored, value in self.items.items() if stored[0] != project_id}
        self.items[(project_id, key)] = [{
            **copy.deepcopy(chunk), "seq": index,
            "embedding": copy.deepcopy(embeddings[index]) if embeddings else None,
            "model": model if embeddings else None,
        } for index, chunk in enumerate(chunks)]


def request(noise=4, mode="ALWAYS", korean_only=False):
    components = [{"path": f"src/{UNRELATED}/noise_{index}.py", "type": "SERVICE", "language": "Python"}
                  for index in range(noise)]
    components.extend({"path": path, "type": "SERVICE", "language": "Python"}
                      for path in (TARGET_FILE, "src/CompletionController.py", "src/CompletionNotes.py"))
    relations = [{"file": f"src/{UNRELATED}/noise_{index}.py", "line": index + 1,
                  "schema": "sample", "table": f"{UNRELATED}_{index}", "kind": "SQL_SELECT"} for index in range(noise)]
    relations += [{"file": TARGET_FILE, "line": 3, "schema": "sample", "table": "completion_records", "kind": "SQL_UPDATE"},
                  {"file": "src/CompletionNotes.py", "line": 7, "schema": "sample", "table": "completion_notes", "kind": "SQL_SELECT"}]
    tables = [{"schema": "sample", "name": f"{UNRELATED}_{index}", "columns": [{"name": "id"}],
               "foreignKeys": [], "indexes": []} for index in range(noise)]
    tables.extend([
        {"schema": "sample", "name": "completion_records", "comment": "목표 완료일을 저장하는 구조입니다.",
         "columns": [{"name": "completion_date", "type": "date"}, {"name": "owner_id", "type": "uuid"}],
         "foreignKeys": [{"column": "owner_id", "referencedSchema": "sample", "referencedTable": "owners", "referencedColumn": "id"}],
         "indexes": [{"name": "completion_date_lookup", "columns": ["completion_date"], "unique": False}]},
        {"schema": "sample", "name": "owners", "columns": [{"name": "id", "type": "uuid"}], "foreignKeys": [], "indexes": []},
        {"schema": "sample", "name": "completion_notes", "comment": "목표 완료일 설명을 저장합니다.",
         "columns": [{"name": "completion_note", "type": "text"}], "foreignKeys": [], "indexes": []},
    ])
    content = "목표 완료일을 저장하고 표시합니다." if korean_only else "completion_date를 저장하고 Completion 화면에서 확인합니다."
    return ProjectAnalysisRequest.model_validate({
        "projectId": "00000000-0000-0000-0000-000000000321",
        "sourceSummary": {"changeRequirement": {"title": "목표 완료일 추가", "refinedContent": content, "evidenceSelection": mode},
                          "components": components, "sourceConnections": {
                              "links": [{"file": "src/CompletionController.py", "line": 2,
                                         "targetFile": TARGET_FILE, "targetLine": 3, "kind": "STATIC_IMPORT_REFERENCE"}],
                              "routes": [{"file": "src/CompletionController.py", "line": 4, "route": "/completion", "kind": "ROUTE"}]
                                        + [{"file": f"src/{UNRELATED}/noise_{index}.py", "line": index + 1,
                                            "route": f"/{UNRELATED}/{index}", "kind": "ROUTE"} for index in range(noise)],
                              "unresolvedReferences": [], "truncated": False,
                          }},
        "databaseSummary": {"type": "POSTGRESQL", "tables": tables}, "relations": relations,
    })


def input_payload(kwargs):
    content = kwargs["messages"][1]["content"]
    prefix = "Analyze these project facts and return only the required JSON object:\n"
    return json.loads(content.split(prefix, 1)[1] if prefix in content else content)


class AnalysisClient:
    """전송된 현재 검토 범위만 인용하고 새 내부 응답 확인값도 실제 입력에서 만든다."""

    def __init__(self, *, requires_full=True, bad_stage=None):
        self.requires_full = requires_full
        self.bad_stage = bad_stage
        self.calls = []

    async def chat(self, **kwargs):
        self.calls.append(copy.deepcopy(kwargs))
        properties = kwargs["format"]["properties"]
        payload = input_payload(kwargs)
        if "databaseNarrative" in properties:
            general = json.loads(REQUIREMENT_GENERAL_MODEL_JSON)
            response = {key: general[key] for key in ("summary", "plainLanguageSummary", "riskAssessment", "impactAreas", "recommendations")}
            supported = payload.get("sourceContext", {}).get("tableReferences", [])
            response["evidences"] = ([{"file": TARGET_FILE, "line": 3, "description": "실제 전달한 저장 참조를 확인했습니다."}]
                                     if any(item.get("file") == TARGET_FILE and item.get("line") == 3 for item in supported) else [])
        elif "status" in properties:
            tables = payload.get("tables", [])
            names = [f"{table['schema']}.{table['name']}" for table in tables]
            if "databaseFacts" in payload:
                names = [table["table"] for table in payload["databaseFacts"]["tables"]]
            response = {"status": "UNKNOWN", "explanation": "관련 구조는 확인했으나 실제 행의 상태는 검증하지 못했습니다.",
                        "observedTables": names, "proposedChanges": [], "conditionalPaths": [], "openQuestions": []}
            if self.bad_stage == "database":
                response["observedTables"] = [f"sample.{UNRELATED}_0"]
        else:
            response = json.loads(REQUIREMENT_GENERAL_MODEL_JSON)
            response["databaseReviewPlan"] = {
                "targetTables": [TARGET], "evidenceFiles": [TARGET_FILE], "requiresFullReview": self.requires_full,
                "reason": "관련 저장 참조와 연결된 구조를 검토합니다.",
            }
            response["evidences"] = ([{"file": f"src/{UNRELATED}/noise_0.py", "line": 1,
                                       "description": "검색에서 제외된 원본 근거를 잘못 인용했습니다."}]
                                     if self.bad_stage == "general" else [])
        content = _enrich_internal_fixture(json.dumps(response, ensure_ascii=False), kwargs)
        return SimpleNamespace(message=SimpleNamespace(content=content))


def config(**changes):
    defaults = {"ai_requirement_fast_path_enabled": True, "ai_final_reconciliation_enabled": True,
                "ai_max_validation_retries": 0, "ai_db_batch_max_tables": 1,
                "ai_db_batch_max_chars": 90000, "ai_db_merge_max_chars": 90000}
    return settings(**{**defaults, **changes})


def assert_only_related_requests(client):
    assert client.calls
    for call in client.calls:
        assert UNRELATED not in json.dumps({"messages": call["messages"], "format": call["format"]}, ensure_ascii=False)
    database = [input_payload(call) for call in client.calls if "status" in call["format"]["properties"]]
    assert database
    for payload in database:
        assert {f"{table['schema']}.{table['name']}" for table in payload.get("tables", [])} <= RELATED
        assert {table["table"] for table in payload.get("databaseFacts", {}).get("tables", [])} <= RELATED


@pytest.mark.parametrize("noise", [4, 80])
@pytest.mark.parametrize("fast_path", [True, False])
def test_always_sends_only_related_evidence_through_general_batches_merge_and_final(noise, fast_path):
    original = request(noise)
    before = original.model_dump(mode="json", by_alias=True)
    client = AnalysisClient(requires_full=True)
    result = asyncio.run(LlmService(config(ai_requirement_fast_path_enabled=fast_path), client=client,
                                   retriever=EvidenceRetriever(MemoryStore())).analyze(original))
    assert_only_related_requests(client)
    general = input_payload(client.calls[0])
    assert {f"{table['schema']}.{table['name']}" for table in general["databaseSummary"]["tables"]} == RELATED
    assert any("reviews" in input_payload(call) for call in client.calls)
    assert "databaseNarrative" in client.calls[-1]["format"]["properties"]
    final = input_payload(client.calls[-1])
    facts = {table["table"]: table for table in final["reconciliationFacts"]["tables"]}
    assert {TARGET, NEIGHBOR} <= facts.keys()
    assert facts[TARGET]["collectedIndexCount"] == 1
    assert result.database_impact.status.value == "UNKNOWN"
    assert set(result.database_impact.observed_tables) == RELATED
    assert original.model_dump(mode="json", by_alias=True) == before


def test_unknown_focused_review_never_reintroduces_original_unrelated_evidence():
    original = request(40)
    client = AnalysisClient(requires_full=False)
    events = []

    async def progress(code, number):
        events.append((code, number))

    result = asyncio.run(LlmService(config(), client=client, retriever=EvidenceRetriever(MemoryStore())).analyze(original, progress))
    assert_only_related_requests(client)
    assert any(code == "DATABASE_SCOPE_FOCUSED" for code, _ in events)
    assert all(number <= len(RELATED) for code, number in events if code.startswith("DATABASE_SCOPE_") and number is not None)
    assert set(result.database_impact.observed_tables) <= RELATED


def test_korean_requirement_selects_related_database_comments_without_original_fallback():
    original = request(20, korean_only=True)
    client = AnalysisClient()
    result = asyncio.run(LlmService(config(), client=client, retriever=EvidenceRetriever(MemoryStore())).analyze(original))
    assert_only_related_requests(client)
    assert TARGET in result.database_impact.observed_tables
    assert NEIGHBOR in result.database_impact.observed_tables


@pytest.mark.parametrize("stage", ["general", "database"])
@pytest.mark.parametrize("fast_path", [True, False])
def test_always_rejects_original_evidence_that_was_not_selected_even_when_fast_path_is_disabled(stage, fast_path):
    client = AnalysisClient(bad_stage=stage)
    with pytest.raises(InvalidModelResponseError):
        asyncio.run(LlmService(config(ai_requirement_fast_path_enabled=fast_path), client=client,
                               retriever=EvidenceRetriever(MemoryStore())).analyze(request()))
    if stage == "database":
        assert_only_related_requests(client)
    assert UNRELATED not in json.dumps(client.calls[0]["format"], ensure_ascii=False)
    assert len(client.calls) == (1 if stage == "general" else 2)


def test_off_keeps_original_full_evidence_for_the_existing_analysis_flow():
    original = request(15, mode="OFF")
    client = AnalysisClient()
    result = asyncio.run(LlmService(config(ai_db_batch_max_tables=40), client=client,
                                   retriever=EvidenceRetriever(MemoryStore())).analyze(original))
    general = input_payload(client.calls[0])
    assert len(general["databaseSummary"]["tables"]) == 18
    assert UNRELATED in json.dumps(general, ensure_ascii=False)
    detail = input_payload(client.calls[1])
    assert len(detail["tables"]) == 18
    assert f"sample.{UNRELATED}_0" in result.database_impact.observed_tables


def test_search_failure_in_always_mode_stops_before_sending_original_evidence(caplog):
    class FailingRetriever:
        embed = None

        async def retrieve(self, payload):
            payload["sourceSummary"]["changeRequirement"]["title"] = "원본에 반영하면 안 되는 변경입니다."
            raise RuntimeError("외부 검색 오류 원문 표식입니다.")

    original = request()
    before = original.model_dump(mode="json", by_alias=True)
    client = AnalysisClient()
    with pytest.raises(ModelUnavailableError):
        asyncio.run(LlmService(config(), client=client, retriever=FailingRetriever()).analyze(original))
    assert client.calls == []
    assert "외부 검색 오류 원문 표식" not in caplog.text
    assert original.model_dump(mode="json", by_alias=True) == before


def test_missing_retriever_in_always_mode_cannot_silently_send_full_evidence():
    client = AnalysisClient()
    with pytest.raises(ModelUnavailableError):
        asyncio.run(LlmService(config(), client=client).analyze(request()))
    assert client.calls == []


class ProposalClient(AnalysisClient):
    """모델 입력에 없는 구조는 추가하지 않고 요청한 DB 변경안만 응답에 넣는다."""

    def __init__(self, change):
        super().__init__()
        self.change = change

    async def chat(self, **kwargs):
        result = await super().chat(**kwargs)
        if "status" in kwargs["format"]["properties"]:
            response = json.loads(result.message.content)
            response.update(status="CHANGE_REQUIRED", observedTables=[TARGET], proposedChanges=[copy.deepcopy(self.change)])
            response.pop("confirmedActions", None)
            result.message.content = _enrich_internal_fixture(json.dumps(response, ensure_ascii=False), kwargs)
        return result


@pytest.mark.parametrize("fast_path", [True, False])
def test_hidden_existing_table_cannot_be_created_again_and_is_not_exposed_to_the_model(fast_path):
    original = request(8)
    hidden = f"sample.{UNRELATED}_0"
    assert hidden in {f"{table['schema']}.{table['name']}" for table in original.database_summary["tables"]}
    client = ProposalClient({
        "changeType": "CREATE_TABLE", "targetTable": hidden, "targetColumn": None, "necessity": "REQUIRED",
        "description": "요청을 저장할 새 구조를 추가합니다.", "rationale": "목표 완료일을 별도 구조에서 관리하기 위해 필요합니다.",
    })
    with pytest.raises(InvalidModelResponseError) as error:
        asyncio.run(LlmService(config(ai_requirement_fast_path_enabled=fast_path, ai_db_batch_max_tables=40), client=client,
                               retriever=EvidenceRetriever(MemoryStore())).analyze(original))
    assert LlmService._safe_validation_code(error.value.__cause__) == "DB_CREATE_EXISTING_TABLE"
    assert len(client.calls) == 2
    assert_only_related_requests(client)
    assert hidden not in json.dumps(client.calls, ensure_ascii=False)


@pytest.mark.parametrize("fast_path", [True, False])
def test_selected_source_reference_beyond_original_context_limit_can_support_a_column_change(fast_path):
    from app.services.source_context import supported_target_tables

    original = request(85)
    before = original.model_dump(mode="json", by_alias=True)
    assert TARGET not in supported_target_tables(before)
    change = {
        "changeType": "ADD_COLUMN", "targetTable": TARGET, "targetColumn": "scheduled_completion", "necessity": "REQUIRED",
        "description": "목표 완료일을 저장하는 새 컬럼을 추가합니다.", "rationale": "요구사항의 완료 예정일을 저장하기 위해 필요합니다.",
    }
    client = ProposalClient(change)
    result = asyncio.run(LlmService(config(ai_requirement_fast_path_enabled=fast_path, ai_db_batch_max_tables=40), client=client,
                                   retriever=EvidenceRetriever(MemoryStore())).analyze(original))
    assert_only_related_requests(client)
    general = input_payload(client.calls[0])
    assert TARGET in supported_target_tables(general)
    assert any(item.get("file") == TARGET_FILE and item.get("line") == 3
               for item in general["sourceSummary"]["sourceContext"]["tableReferences"])
    assert result.database_impact.status.value == "CHANGE_REQUIRED"
    assert result.database_impact.proposed_changes[0].target_table == TARGET
    assert result.database_impact.proposed_changes[0].target_column == "scheduled_completion"
    assert "databaseNarrative" in client.calls[-1]["format"]["properties"]
    assert original.model_dump(mode="json", by_alias=True) == before
