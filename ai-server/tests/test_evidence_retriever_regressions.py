import asyncio
import copy

import pytest

from app.services.evidence_retriever import EvidenceRetriever, build_chunks, evidence_key, query_text


class MemoryStore:
    """파일을 만들지 않고 운영 저장소와 같은 색인 읽기·쓰기 계약을 제공한다."""

    def __init__(self):
        self.entries = {}
        self.saves = 0

    def has(self, project_id, key):
        return bool(self.entries.get((project_id, key)))

    def save(self, project_id, key, chunks, embeddings, model):
        self.saves += 1
        self.entries = {stored_key: value for stored_key, value in self.entries.items()
                        if stored_key[0] != project_id}
        self.entries[(project_id, key)] = [{
            **copy.deepcopy(chunk), "seq": index,
            "embedding": copy.deepcopy(embeddings[index]) if embeddings else None,
            "model": model if embeddings else None,
        } for index, chunk in enumerate(chunks)]

    def load(self, project_id, key):
        return copy.deepcopy(self.entries.get((project_id, key), []))


class EmbeddingClient:
    """정해진 입력으로 벡터를 만들고 호출·일시 실패만 기록하는 대역이다."""

    def __init__(self, failures=0, incomplete=False):
        self.calls = []
        self.failures = failures
        self.incomplete = incomplete

    async def __call__(self, **kwargs):
        self.calls.append(copy.deepcopy(kwargs))
        if self.failures:
            self.failures -= 1
            raise ConnectionError("임베딩 대역의 일시 연결 실패입니다.")
        vectors = [[float(len(text) % 7), 1.0] for text in kwargs["input"]]
        return {"embeddings": vectors[:-1] if self.incomplete else vectors}


def analysis_payload(table_count=2):
    return {
        "projectId": "00000000-0000-0000-0000-000000000001",
        "sourceSummary": {
            "changeRequirement": {"title": "저장 정보 변경", "refinedContent": "입력한 정보를 저장합니다.", "acceptanceCriteria": []},
            "components": [],
        },
        "databaseSummary": {
            "type": "POSTGRESQL", "tables": [
                {"schema": "sample", "name": f"item_{index}", "columns": [{"name": "record_id", "type": "integer"}]}
                for index in range(table_count)
            ],
        },
        "relations": [],
    }


def selected_chunks(payload, **selection):
    chunks = build_chunks(payload)
    chosen = {index for index, chunk in enumerate(chunks)
              if any(chunk["kind"] == kind and predicate(chunk["item"]) for kind, predicate in selection.items())}
    return chunks, chosen


def test_search_query_includes_nested_acceptance_values_and_human_context_only():
    requirement = {
        "title": "공통 제목입니다.", "refinedContent": "공통 본문입니다.",
        "acceptanceCriteria": ["deadline_at을 저장합니다.", {"scenario": "expected_finish_at을 표시합니다.",
                                                          "checks": ["completed_at을 검증합니다."]}],
        "latestFollowUpQuestion": "사람이 추가한 latest_context를 확인합니다.",
        "clarifications": [
            {"kind": "AI_QUESTION", "question": "automated_unrelated_table도 바꿀까요?", "answer": "human_answer는 유지합니다."},
            {"kind": "SYSTEM_QUESTION", "question": "system_generated_index도 필요할까요?", "answer": "두 번째 사람 답변입니다."},
            {"kind": "USER_QUESTION", "question": "user_target_column을 추가합니다.", "answer": "직접 작성한 답변입니다."},
            {"question": "unclassified_automatic_question은 제외합니다."},
        ],
    }
    before = copy.deepcopy(requirement)
    query = query_text(requirement)
    for expected in ("공통 제목", "공통 본문", "deadline_at", "expected_finish_at", "completed_at", "latest_context",
                     "human_answer", "두 번째 사람 답변", "user_target_column", "직접 작성한 답변"):
        assert expected in query
    for excluded in ("automated_unrelated_table", "system_generated_index", "unclassified_automatic_question"):
        assert excluded not in query
    assert requirement == before


@pytest.mark.parametrize("requirement", [None, [], "요구사항이 아닌 값"])
def test_invalid_requirement_does_not_generate_a_search_query(requirement):
    assert query_text(requirement) == ""


def test_target_named_only_in_acceptance_criteria_survives_the_table_budget():
    payload = analysis_payload(13)
    target = payload["databaseSummary"]["tables"][12]
    target["name"] = "completion_schedule"
    target["columns"] = [{"name": "expected_completion_date", "type": "date"}]
    payload["sourceSummary"]["changeRequirement"]["acceptanceCriteria"] = ["expected_completion_date를 저장하고 표시합니다."]
    before = copy.deepcopy(payload)
    store = MemoryStore()
    reduced = asyncio.run(EvidenceRetriever(store).retrieve(payload))
    names = {table["name"] for table in reduced["databaseSummary"]["tables"]}
    assert "completion_schedule" in names
    assert len(names) < 13
    assert reduced["sourceSummary"]["changeRequirement"] == payload["sourceSummary"]["changeRequirement"]
    assert payload == before
    assert store.saves == 1


def test_an_existing_lexical_index_is_embedded_after_enabling_an_embedding_model():
    async def scenario():
        payload, store = analysis_payload(), MemoryStore()
        key = await EvidenceRetriever(store).index(payload)
        lexical = store.load(payload["projectId"], key)
        assert lexical and all(chunk["embedding"] is None for chunk in lexical)
        embedding = EmbeddingClient()
        retriever = EvidenceRetriever(store, embedding, "embedding-example-a")
        assert await retriever.index(payload) == key
        stored = store.load(payload["projectId"], key)
        assert len(embedding.calls) == 1
        assert all(chunk["model"] == "embedding-example-a" and chunk["embedding"] for chunk in stored)
        assert [chunk["item"] for chunk in stored] == [chunk["item"] for chunk in lexical]
        assert await retriever.index(payload) == key
        assert len(embedding.calls) == 1
    asyncio.run(scenario())


@pytest.mark.parametrize("failure_kind", ["exception", "incomplete"])
def test_temporary_embedding_failures_are_retried_and_success_is_cached(failure_kind):
    async def scenario():
        payload, store = analysis_payload(), MemoryStore()
        embedding = EmbeddingClient(failures=1 if failure_kind == "exception" else 0,
                                    incomplete=failure_kind == "incomplete")
        retriever = EvidenceRetriever(store, embedding, "embedding-example-a")
        key = await retriever.index(payload)
        assert all(chunk["embedding"] is None for chunk in store.load(payload["projectId"], key))
        embedding.incomplete = False
        assert await retriever.index(payload) == key
        assert all(chunk["embedding"] for chunk in store.load(payload["projectId"], key))
        assert len(embedding.calls) == 2
        await retriever.index(payload)
        assert len(embedding.calls) == 2
    asyncio.run(scenario())


def test_switching_embedding_models_rebuilds_vectors_without_changing_evidence_identity():
    async def scenario():
        payload, store = analysis_payload(), MemoryStore()
        embedding = EmbeddingClient()
        first = EvidenceRetriever(store, embedding, "embedding-example-a")
        key = await first.index(payload)
        second = EvidenceRetriever(store, embedding, "embedding-example-b")
        assert await second.index(payload) == key == evidence_key(payload)
        assert [call["model"] for call in embedding.calls] == ["embedding-example-a", "embedding-example-b"]
        assert all(chunk["model"] == "embedding-example-b" for chunk in store.load(payload["projectId"], key))
        await second.index(payload)
        assert len(embedding.calls) == 2
    asyncio.run(scenario())


def test_reduction_records_database_omissions_without_mutating_original_metadata():
    payload = analysis_payload(3)
    payload["databaseSummary"].update(truncated=False, warnings=["상위 수집 경고를 유지합니다."], collectedTableCount=9)
    chunks, selected = selected_chunks(payload, dbTable=lambda table: table["name"] == "item_0")
    before = copy.deepcopy(payload)
    reduced = EvidenceRetriever._reduce(payload, chunks, selected)
    assert reduced["databaseSummary"]["truncated"] is True
    assert reduced["databaseSummary"]["warnings"] == payload["databaseSummary"]["warnings"]
    assert reduced["databaseSummary"]["collectedTableCount"] == 9
    assert reduced["databaseSummary"]["retrieval"] == {
        "mode": "RAG", "inputTableCount": 3, "includedTableCount": 1,
        "omittedTables": ["sample.item_1", "sample.item_2"], "originalTruncated": False,
    }
    retrieval = reduced["sourceSummary"]["promptCoverage"]["retrieval"]
    assert retrieval["total"]["dbTable"] == 3
    assert retrieval["selected"]["dbTable"] == 1
    assert retrieval["omitted"]["dbTable"] == 2
    assert retrieval["originalTruncated"] is False
    assert "sourceConnections" not in reduced["sourceSummary"]
    assert payload == before


def test_foreign_key_neighbors_are_retained_even_when_they_were_not_selected_directly():
    payload = analysis_payload(3)
    payload["databaseSummary"]["tables"][0]["foreignKeys"] = [{"column": "parent_id", "referencedSchema": "sample", "referencedTable": "item_1"}]
    chunks, selected = selected_chunks(payload, dbTable=lambda table: table["name"] == "item_0")
    reduced = EvidenceRetriever._reduce(payload, chunks, selected)
    assert {table["name"] for table in reduced["databaseSummary"]["tables"]} == {"item_0", "item_1"}
    assert reduced["databaseSummary"]["truncated"] is True


def test_reduction_retains_graph_omissions_and_upstream_truncation():
    payload = analysis_payload(1)
    payload["sourceSummary"]["components"] = [{"path": "src/retained.py", "type": "SOURCE"}, {"path": "src/omitted.py", "type": "SOURCE"}]
    payload["sourceSummary"]["sourceConnections"] = {
        "truncated": True,
        "links": [{"file": "src/retained.py", "targetFile": "src/store.py", "line": 2},
                  {"file": "src/omitted.py", "targetFile": "src/other.py", "line": 5}],
        "routes": [{"file": "src/retained.py", "route": "/retained", "line": 8},
                   {"file": "src/omitted.py", "route": "/omitted", "line": 9}],
        "unresolvedReferences": [{"file": "src/omitted.py", "line": 12, "symbol": "unknown_symbol"}],
        "inspectedLanguages": ["Python"], "unsupportedLanguages": ["Other"],
        "collectedLinks": 9, "collectedRoutes": 7, "collectedUnresolvedReferences": 4,
    }
    chunks, selected = selected_chunks(payload, dbTable=lambda table: True,
                                       link=lambda link: link["file"] == "src/retained.py",
                                       route=lambda route: route["file"] == "src/retained.py")
    before = copy.deepcopy(payload)
    reduced = EvidenceRetriever._reduce(payload, chunks, selected)
    graph = reduced["sourceSummary"]["sourceConnections"]
    assert graph["truncated"] is True
    assert len(graph["links"]) == 1 and len(graph["routes"]) == 1
    assert graph["inspectedLanguages"] == ["Python"]
    assert graph["unsupportedLanguages"] == ["Other"]
    assert graph["collectedLinks"] == 9 and graph["collectedRoutes"] == 7
    assert graph["collectedUnresolvedReferences"] == 4
    assert graph["includedLinks"] == 1 and graph["includedRoutes"] == 1
    assert graph["includedUnresolvedReferences"] == 0
    retrieval = reduced["sourceSummary"]["promptCoverage"]["retrieval"]
    assert retrieval["omitted"]["link"] == 1 and retrieval["omitted"]["route"] == 1
    assert retrieval["omitted"]["unresolved"] == 1
    assert reduced["sourceSummary"]["promptCoverage"]["truncated"] is True
    assert payload == before


@pytest.mark.parametrize("upstream_truncated", [False, True])
def test_retaining_every_chunk_does_not_invent_new_omissions_or_clear_upstream_flags(upstream_truncated):
    payload = analysis_payload(2)
    payload["databaseSummary"].update(truncated=upstream_truncated, warnings=[])
    payload["sourceSummary"]["sourceConnections"] = {"links": [], "routes": [], "unresolvedReferences": [], "truncated": upstream_truncated}
    payload["sourceSummary"]["promptCoverage"] = {"truncated": upstream_truncated, "notice": "이전 안내를 유지합니다."}
    chunks = build_chunks(payload)
    reduced = EvidenceRetriever._reduce(payload, chunks, set(range(len(chunks))))
    assert reduced["databaseSummary"]["truncated"] is upstream_truncated
    assert reduced["sourceSummary"]["sourceConnections"]["truncated"] is upstream_truncated
    assert reduced["sourceSummary"]["promptCoverage"]["truncated"] is upstream_truncated
    assert reduced["sourceSummary"]["promptCoverage"]["notice"] == "이전 안내를 유지합니다."
    assert reduced["databaseSummary"]["retrieval"] == {
        "mode": "RAG", "inputTableCount": 2, "includedTableCount": 2,
        "omittedTables": [], "originalTruncated": upstream_truncated,
    }
    retrieval = reduced["sourceSummary"]["promptCoverage"]["retrieval"]
    assert all(omitted == 0 for omitted in retrieval["omitted"].values())
    assert retrieval["originalTruncated"] is upstream_truncated


def test_a_failed_embedding_model_switch_preserves_the_last_successful_index():
    async def scenario():
        payload, store = analysis_payload(), MemoryStore()
        embedding = EmbeddingClient()
        key = await EvidenceRetriever(store, embedding, "embedding-example-a").index(payload)
        previous = store.load(payload["projectId"], key)
        embedding.failures = 1
        changed = EvidenceRetriever(store, embedding, "embedding-example-b")
        assert await changed.index(payload) == key
        assert store.load(payload["projectId"], key) == previous
        assert await changed.index(payload) == key
        assert all(chunk["model"] == "embedding-example-b" for chunk in store.load(payload["projectId"], key))
        assert [call["model"] for call in embedding.calls] == ["embedding-example-a", "embedding-example-b", "embedding-example-b"]
    asyncio.run(scenario())


def test_graph_omissions_set_truncation_even_when_database_tables_are_all_retained():
    payload = analysis_payload(1)
    payload["databaseSummary"].update(truncated=False, warnings=[])
    payload["sourceSummary"]["sourceConnections"] = {
        "truncated": False,
        "links": [{"file": "src/one.py", "targetFile": "src/store.py"},
                  {"file": "src/two.py", "targetFile": "src/other.py"}],
        "routes": [], "unresolvedReferences": [],
    }
    chunks, selected = selected_chunks(payload, dbTable=lambda table: True,
                                       link=lambda link: link["file"] == "src/one.py")
    reduced = EvidenceRetriever._reduce(payload, chunks, selected)
    assert reduced["sourceSummary"]["sourceConnections"]["truncated"] is True
    assert reduced["databaseSummary"].get("truncated", False) is False
    assert reduced["databaseSummary"]["retrieval"]["omittedTables"] == []
    assert reduced["sourceSummary"]["promptCoverage"]["retrieval"]["omitted"]["link"] == 1


def test_a_new_requirement_reuses_the_evidence_index_but_selects_its_own_target():
    async def scenario():
        payload, store = analysis_payload(13), MemoryStore()
        tables = payload["databaseSummary"]["tables"]
        tables[0]["columns"] = [{"name": "alphauniquefield"}]
        tables[12]["columns"] = [{"name": "omegaspecialvalue"}]
        payload["sourceSummary"]["changeRequirement"]["acceptanceCriteria"] = ["alphauniquefield를 저장합니다."]
        retriever = EvidenceRetriever(store)
        first = await retriever.retrieve(payload)
        changed = copy.deepcopy(payload)
        changed["sourceSummary"]["changeRequirement"]["acceptanceCriteria"] = ["omegaspecialvalue를 저장합니다."]
        second = await retriever.retrieve(changed)
        assert {table["name"] for table in first["databaseSummary"]["tables"]} == {"item_0"}
        assert {table["name"] for table in second["databaseSummary"]["tables"]} == {"item_12"}
        assert evidence_key(payload) == evidence_key(changed)
        assert store.saves == 1
    asyncio.run(scenario())
