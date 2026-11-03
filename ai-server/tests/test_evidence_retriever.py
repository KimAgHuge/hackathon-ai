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


def test_always_mode_leaves_evidence_empty_when_nothing_is_related(tmp_path):
    # 한글 요구사항은 영어 식별자와 낱말이 겹치지 않으므로 관련 조각이 없다. 앞쪽 항목으로 채우지 않는다.
    request = payload({"title": "주문 취소", "refinedContent": "사유 저장", "evidenceSelection": "ALWAYS"})
    reduced = asyncio.run(retriever(tmp_path).retrieve(request))

    assert reduced["sourceSummary"]["components"] == []
    assert reduced["relations"] == [] and reduced["databaseSummary"]["tables"] == []
    retrieval = reduced["sourceSummary"]["promptCoverage"]["retrieval"]
    assert retrieval["noRelevantEvidence"] is True and retrieval["userSelected"] is True
    assert "관련된 근거를 찾지 못" in retrieval["notice"]
    assert len(request["databaseSummary"]["tables"]) == 82


def test_always_mode_does_not_expand_by_file_or_table_name(tmp_path):
    request = payload({"title": "취소", "refinedContent": "OrderCancel", "evidenceSelection": "ALWAYS"}, noise=3)
    request["sourceSummary"]["components"].append({"path": "src/billing/InvoiceRepository.java", "type": "REPOSITORY", "language": "java"})
    request["relations"].append({"file": "src/billing/InvoiceRepository.java", "schema": "public", "table": "orders", "kind": "SELECT"})
    reduced = asyncio.run(retriever(tmp_path).retrieve(request))

    # OrderCancel과 겹치는 파일만 남고, 같은 테이블을 읽는다는 이유로 다른 파일이 따라오지 않는다.
    assert [item["path"] for item in reduced["sourceSummary"]["components"]] == ["src/order/OrderCancelService.java"]
    assert [item["file"] for item in reduced["relations"]] == ["src/order/OrderCancelService.java"]
    assert {table["name"] for table in reduced["databaseSummary"]["tables"]} == {"orders", "customers"}
    assert "noRelevantEvidence" not in reduced["sourceSummary"]["promptCoverage"]["retrieval"]


def test_default_mode_still_fills_from_the_front_when_nothing_matches(tmp_path):
    reduced = asyncio.run(retriever(tmp_path).retrieve(payload({"title": "주문 취소"})))
    # 기존 방식은 점수가 없어도 앞쪽 항목으로 채우고 연결된 파일까지 넓힌다. 이 동작은 바뀌지 않는다.
    assert len(reduced["sourceSummary"]["components"]) >= 40
    assert "userSelected" not in reduced["sourceSummary"]["promptCoverage"]["retrieval"]


def test_always_mode_ignores_weak_embedding_similarity(tmp_path):
    async def embed(model, input):
        # 질문(사유)과 주문 취소 파일은 같은 방향이고, 나머지는 거의 수직이라 유사도가 바닥값보다 낮다.
        related = ("사유", "OrderCancel", "cancel_reason")
        return {"embeddings": [[1.0, 0.0] if any(word in text for word in related) else [0.1, 1.0] for text in input]}

    request = payload({"title": "주문 취소", "refinedContent": "사유", "evidenceSelection": "ALWAYS"}, noise=3)
    reduced = asyncio.run(EvidenceRetriever(EvidenceStore(tmp_path / "e.db"), embed, "bge-m3").retrieve(request))
    assert [item["path"] for item in reduced["sourceSummary"]["components"]] == ["src/order/OrderCancelService.java"]


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


def test_index_built_without_embeddings_is_rebuilt_when_model_is_enabled(tmp_path):
    async def embed(model, input):
        return {"embeddings": [[1.0, 0.0] for _ in input]}

    request = payload(None, noise=2)
    store = EvidenceStore(tmp_path / "old.db")
    key = asyncio.run(EvidenceRetriever(store).index(request))
    assert all(chunk["embedding"] is None for chunk in store.load(PROJECT_ID, key))

    assert asyncio.run(EvidenceRetriever(store, embed, "bge-m3").index(request)) == key
    assert all(chunk["embedding"] == [1.0, 0.0] and chunk["model"] == "bge-m3" for chunk in store.load(PROJECT_ID, key))
