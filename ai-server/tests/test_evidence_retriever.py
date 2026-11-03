import asyncio

from app.services.evidence_retriever import EvidenceRetriever, EvidenceStore, build_chunks, tokenize

PROJECT_ID = "11111111-1111-1111-1111-111111111111"


def payload(requirement, noise=80):
    components = [{"path": f"src/noise/Util{i}.java", "type": "SERVICE", "language": "java"} for i in range(noise)]
    components.append({"path": "src/order/OrderCancelService.java", "type": "SERVICE", "language": "java"})
    relations = [{"file": f"src/noise/Util{i}.java", "schema": "public", "table": f"misc_{i}", "kind": "SELECT"} for i in range(noise)]
    relations.append({"file": "src/order/OrderCancelService.java", "schema": "public", "table": "orders", "kind": "UPDATE"})
    tables = [{"schema": "public", "name": f"misc_{i}", "columns": [{"name": "id"}], "foreignKeys": []} for i in range(noise)]
    tables += [{"schema": "public", "name": "orders", "columns": [{"name": "cancel_reason"}],
                "foreignKeys": [{"referencedSchema": "public", "referencedTable": "customers"}]},
               {"schema": "public", "name": "customers", "columns": [{"name": "id"}], "foreignKeys": []}]
    source = {"components": components, "sourceConnections": {"links": [], "routes": [], "unresolvedReferences": []}}
    if requirement:
        source["changeRequirement"] = requirement
    return {"projectId": PROJECT_ID, "sourceSummary": source, "databaseSummary": {"tables": tables}, "relations": relations}


def retriever(tmp_path):
    return EvidenceRetriever(EvidenceStore(tmp_path / "index.db"))


def test_retrieval_keeps_only_related_evidence(tmp_path):
    request = payload({"title": "주문 취소", "refinedContent": "OrderCancel 사유 cancel_reason 저장"})
    reduced = asyncio.run(retriever(tmp_path).retrieve(request))

    assert reduced is not request
    assert any(item["path"].endswith("OrderCancelService.java") for item in reduced["sourceSummary"]["components"])
    assert len(reduced["sourceSummary"]["components"]) < 82
    names = {table["name"] for table in reduced["databaseSummary"]["tables"]}
    assert {"orders", "customers"} <= names and len(names) <= 14
    assert any(item["table"] == "orders" for item in reduced["relations"])
    coverage = reduced["sourceSummary"]["promptCoverage"]
    assert coverage["truncated"] is True and coverage["retrieval"]["mode"] == "RAG"
    assert coverage["retrieval"]["total"]["dbTable"] == 82
    assert len(request["databaseSummary"]["tables"]) == 82


def test_small_evidence_is_left_untouched(tmp_path):
    request = payload({"title": "주문 취소"}, noise=3)
    assert asyncio.run(retriever(tmp_path).retrieve(request)) is request


def test_always_mode_selects_related_evidence_even_when_small(tmp_path):
    request = payload({"title": "주문 취소", "refinedContent": "OrderCancel 사유", "evidenceSelection": "ALWAYS"}, noise=3)
    reduced = asyncio.run(retriever(tmp_path).retrieve(request))

    assert reduced is not request
    paths = [item["path"] for item in reduced["sourceSummary"]["components"]]
    assert paths == ["src/order/OrderCancelService.java"]
    assert "evidenceSelection" not in reduced["sourceSummary"]["changeRequirement"]
    assert reduced["sourceSummary"]["promptCoverage"]["retrieval"]["mode"] == "RAG"


def test_off_mode_keeps_everything_even_when_large(tmp_path):
    request = payload({"title": "주문 취소", "refinedContent": "OrderCancel 사유", "evidenceSelection": "OFF"})
    result = asyncio.run(retriever(tmp_path).retrieve(request))

    assert len(result["sourceSummary"]["components"]) == 81
    assert len(result["databaseSummary"]["tables"]) == 82
    assert "evidenceSelection" not in result["sourceSummary"]["changeRequirement"]
    assert "promptCoverage" not in result["sourceSummary"]


def test_unknown_or_auto_mode_behaves_like_default(tmp_path):
    small = payload({"title": "주문 취소", "evidenceSelection": "AUTO"}, noise=3)
    assert len(asyncio.run(retriever(tmp_path).retrieve(small))["sourceSummary"]["components"]) == 4
    large = payload({"title": "주문 취소", "refinedContent": "OrderCancel", "evidenceSelection": "weird"})
    assert len(asyncio.run(retriever(tmp_path).retrieve(large))["sourceSummary"]["components"]) < 81


def test_index_is_reused_and_replaced_per_project(tmp_path):
    engine = retriever(tmp_path)
    first = asyncio.run(engine.index(payload(None)))
    assert asyncio.run(engine.index(payload({"title": "무관한 질문"}))) == first
    changed = payload(None, noise=5)
    second = asyncio.run(engine.index(changed))
    assert second != first and not engine.store.has(PROJECT_ID, first)


def test_embeddings_are_stored_and_failure_falls_back(tmp_path):
    async def embed(model, input):
        return {"embeddings": [[1.0, 0.0] for _ in input]}

    async def broken(model, input):
        raise RuntimeError("model missing")

    request = payload({"title": "cancel_reason"})
    with_vectors = EvidenceRetriever(EvidenceStore(tmp_path / "a.db"), embed, "bge-m3")
    key = asyncio.run(with_vectors.index(request))
    assert all(chunk["embedding"] == [1.0, 0.0] for chunk in with_vectors.store.load(PROJECT_ID, key))
    assert asyncio.run(with_vectors.retrieve(request))["sourceSummary"]["promptCoverage"]["retrieval"]["mode"] == "RAG"

    fallback = EvidenceRetriever(EvidenceStore(tmp_path / "b.db"), broken, "bge-m3")
    assert asyncio.run(fallback.retrieve(request))["sourceSummary"]["promptCoverage"]["retrieval"]["mode"] == "RAG"


def test_tokenize_splits_identifiers_and_korean():
    assert {"order", "cancel", "service"} <= set(tokenize("OrderCancelService"))
    assert {"cancel", "reason"} <= set(tokenize("cancel_reason"))
    assert "주문" in tokenize("주문취소") and "취소" in tokenize("주문취소")


def test_chunks_cover_every_evidence_kind():
    request = payload(None, noise=1)
    request["sourceSummary"]["sourceConnections"] = {"links": [{"file": "a"}], "routes": [{"file": "a"}], "unresolvedReferences": [{"file": "a"}]}
    assert {chunk["kind"] for chunk in build_chunks(request)} == {"component", "tableRef", "link", "route", "unresolved", "dbTable"}
