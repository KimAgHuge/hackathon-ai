import asyncio
import copy
import hashlib
import json

import pytest

from app.services.evidence_retriever import EvidenceRetriever, build_chunks, evidence_key
from tests.test_evidence_retriever_regressions import MemoryStore, analysis_payload, selected_chunks


def strict_payload(count=3):
    payload = analysis_payload(count)
    payload["sourceSummary"]["changeRequirement"] = {"title": "uniquetargetvalue", "evidenceSelection": "ALWAYS"}
    if count:
        payload["databaseSummary"]["tables"][0]["columns"] = [{"name": "uniquetargetvalue"}]
    return payload


def test_small_strict_search_never_fills_zero_score_kinds_with_unrelated_evidence():
    payload = strict_payload()
    payload["sourceSummary"]["components"] = [{"path": "src/unrelated.py"}]
    payload["sourceSummary"]["sourceConnections"] = {"links": [], "routes": [{"file": "src/unrelated.py", "route": "/unrelated"}],
                                                      "unresolvedReferences": []}
    before = copy.deepcopy(payload)

    reduced = asyncio.run(EvidenceRetriever(MemoryStore()).retrieve(payload))

    assert reduced["sourceSummary"]["components"] == []
    assert reduced["sourceSummary"]["sourceConnections"]["routes"] == []
    assert [table["name"] for table in reduced["databaseSummary"]["tables"]] == ["item_0"]
    retrieval = reduced["sourceSummary"]["promptCoverage"]["retrieval"]
    assert retrieval["selectionMode"] == "ALWAYS" and retrieval["directMatchCount"] == 1
    assert "unrelated" not in json.dumps(reduced, ensure_ascii=False)
    assert payload == before


@pytest.mark.parametrize("query,reason", [("totallyunmatchedquery", "NO_MATCHES"), ("", "EMPTY_QUERY"), ("   ", "EMPTY_QUERY")])
def test_empty_strict_results_are_explicit_and_do_not_fall_back_to_the_original(query, reason):
    payload = strict_payload()
    payload["sourceSummary"]["changeRequirement"]["title"] = query
    payload["databaseSummary"]["warnings"] = ["수집 경고를 유지합니다."]
    payload["sourceSummary"]["promptCoverage"] = {"collectedTables": 8, "truncated": True}
    before = copy.deepcopy(payload)

    reduced = asyncio.run(EvidenceRetriever(MemoryStore()).retrieve(payload))

    assert reduced is not payload
    assert reduced["sourceSummary"]["components"] == reduced["databaseSummary"]["tables"] == reduced["relations"] == []
    coverage = reduced["sourceSummary"]["promptCoverage"]
    assert coverage["collectedTables"] == 8 and coverage["truncated"] is True
    assert coverage["retrieval"]["emptyReason"] == reason
    assert coverage["retrieval"]["total"]["dbTable"] == 3
    assert coverage["retrieval"]["omitted"]["dbTable"] == 3
    assert reduced["databaseSummary"]["warnings"] == payload["databaseSummary"]["warnings"]
    assert "evidenceSelection" not in reduced["sourceSummary"]["changeRequirement"]
    assert payload == before


def test_empty_stored_chunks_do_not_reuse_full_payload_and_keep_original_totals():
    class EmptyStore(MemoryStore):
        def load(self, project_id, key):
            return []

    reduced = asyncio.run(EvidenceRetriever(EmptyStore()).retrieve(strict_payload()))

    assert reduced["databaseSummary"]["tables"] == []
    retrieval = reduced["sourceSummary"]["promptCoverage"]["retrieval"]
    assert retrieval["emptyReason"] == "EMPTY_INDEX" and retrieval["total"]["dbTable"] == 3


def test_a_truly_empty_index_is_a_valid_empty_projection():
    payload = strict_payload(0)
    reduced = asyncio.run(EvidenceRetriever(MemoryStore()).retrieve(payload))
    retrieval = reduced["sourceSummary"]["promptCoverage"]["retrieval"]
    assert retrieval["emptyReason"] == "EMPTY_INDEX"
    assert all(count == 0 for count in retrieval["total"].values())
    assert reduced["databaseSummary"]["truncated"] is None
    assert reduced["databaseSummary"]["coverageUnknown"] is True


def test_a_table_hit_does_not_reintroduce_every_file_that_references_the_table():
    payload = strict_payload()
    payload["sourceSummary"]["components"] = [{"path": f"src/unrelated_{index}.py"} for index in range(30)]
    payload["relations"] = [{"file": item["path"], "schema": "sample", "table": "item_0"}
                            for item in payload["sourceSummary"]["components"]]
    chunks, selected = selected_chunks(payload, dbTable=lambda table: table["name"] == "item_0")

    reduced = EvidenceRetriever._reduce(payload, chunks, selected)

    assert reduced["sourceSummary"]["components"] == reduced["relations"] == []
    assert [table["name"] for table in reduced["databaseSummary"]["tables"]] == ["item_0"]
    assert reduced["sourceSummary"]["promptCoverage"]["retrieval"]["omitted"]["tableRef"] == 30


def test_a_file_hit_keeps_one_actual_link_step_and_its_table_but_not_unrelated_shared_table_files():
    payload = strict_payload(4)
    source = payload["sourceSummary"]
    source["components"] = [{"path": path} for path in ("src/selected.py", "src/linked.py", "src/far.py", "src/unrelated.py")]
    source["sourceConnections"] = {"links": [
        {"file": "src/selected.py", "targetFile": "src/linked.py", "line": 3},
        {"file": "src/linked.py", "targetFile": "src/far.py", "line": 4},
        {"file": "src/unrelated.py", "targetFile": "src/far.py", "line": 5},
    ], "routes": [{"file": "src/selected.py", "route": "/selected"}, {"file": "src/unrelated.py", "route": "/unrelated"}],
        "unresolvedReferences": []}
    payload["relations"] = [{"file": "src/linked.py", "schema": "sample", "table": "item_0", "line": 7},
                            {"file": "src/unrelated.py", "schema": "sample", "table": "item_0", "line": 8},
                            {"file": "src/far.py", "schema": "sample", "table": "item_3", "line": 9}]
    payload["databaseSummary"]["tables"][0]["foreignKeys"] = [{"referencedSchema": "sample", "referencedTable": "item_1"}]
    payload["databaseSummary"]["tables"][1]["foreignKeys"] = [{"referencedSchema": "sample", "referencedTable": "item_2"}]
    chunks, selected = selected_chunks(payload, component=lambda component: component["path"] == "src/selected.py")
    before = copy.deepcopy(payload)

    reduced = EvidenceRetriever._reduce(payload, chunks, selected)

    assert [item["path"] for item in reduced["sourceSummary"]["components"]] == ["src/selected.py", "src/linked.py"]
    assert reduced["relations"] == [payload["relations"][0]]
    assert [table["name"] for table in reduced["databaseSummary"]["tables"]] == ["item_0", "item_1"]
    assert reduced["sourceSummary"]["sourceConnections"]["links"] == [source["sourceConnections"]["links"][0]]
    assert reduced["sourceSummary"]["sourceConnections"]["routes"] == [source["sourceConnections"]["routes"][0]]
    assert payload == before


def test_stale_derived_inputs_and_omitted_name_lists_do_not_reenter_strict_requests():
    payload = strict_payload()
    source = payload["sourceSummary"]
    source["sourceContext"] = {"routes": [{"file": "src/stale_unrelated.py"}]}
    source["databaseReviewScope"] = {"reviewedTables": ["sample.stale_unrelated"]}
    source["promptCoverage"] = {"collectedTables": 15, "truncated": False,
                                "omittedTables": ["sample.stale_unrelated"], "retrieval": {"selectedNames": ["src/stale_unrelated.py"]}}
    source["scanMetrics"] = {"scannedFileCount": 100, "omittedFiles": ["src/stale_unrelated.py"]}
    source["sourceConnections"] = {"links": [], "routes": [], "unresolvedReferences": [],
                                    "collectedLinks": 10, "cachedPaths": ["src/stale_unrelated.py"]}
    source["frameworks"] = [{"name": "Example", "evidences": [{"file": "src/stale_unrelated.py"}]}]
    payload["databaseSummary"]["databaseFacts"] = {"tables": ["sample.stale_unrelated"]}
    payload["reconciliationFacts"] = {"tables": ["sample.stale_unrelated"]}
    for owner in (payload, source, payload["databaseSummary"]):
        for name in ("supportedTargetTables", "tableCatalog", "observedConstraints", "retrieval"):
            owner[name] = ["sample.stale_unrelated"]

    reduced = asyncio.run(EvidenceRetriever(MemoryStore()).retrieve(payload))

    assert "stale_unrelated" not in json.dumps(reduced)
    assert "sourceContext" not in reduced["sourceSummary"]
    assert reduced["sourceSummary"]["scanMetrics"] == {"scannedFileCount": 100}
    assert reduced["sourceSummary"]["promptCoverage"]["collectedTables"] == 15
    assert reduced["sourceSummary"]["sourceConnections"]["collectedLinks"] == 10
    assert reduced["sourceSummary"]["frameworks"] == [{"name": "Example", "evidences": [],
                                                       "collectedEvidenceCount": 1, "includedEvidenceCount": 0}]
    retrieval = reduced["databaseSummary"]["retrieval"]
    assert "omittedTables" not in retrieval and retrieval["omittedTableCount"] == 2


@pytest.mark.parametrize("first_schema", ["one", "sample"])
def test_strict_foreign_keys_do_not_guess_between_ambiguous_schemas(first_schema):
    payload = strict_payload()
    tables = payload["databaseSummary"]["tables"]
    tables[0]["foreignKeys"] = [{"referencedTable": "shared"}]
    tables[1].update(schema=first_schema, name="shared")
    tables[2].update(schema="two", name="shared")
    chunks, selected = selected_chunks(payload, dbTable=lambda table: table["name"] == "item_0")

    reduced = EvidenceRetriever._reduce(payload, chunks, selected)

    assert [table["name"] for table in reduced["databaseSummary"]["tables"]] == ["item_0"]


def test_missing_table_metadata_stays_distinct_from_a_table_reference():
    payload = strict_payload()
    payload["sourceSummary"]["components"] = [{"path": "src/selected.py"}]
    payload["relations"] = [{"file": "src/selected.py", "schema": "missing", "table": "uncollected", "line": 4}]
    chunks, selected = selected_chunks(payload, component=lambda item: True)

    reduced = EvidenceRetriever._reduce(payload, chunks, selected)

    assert reduced["relations"] == payload["relations"]
    assert reduced["databaseSummary"]["tables"] == []
    assert reduced["sourceSummary"]["promptCoverage"]["retrieval"]["omitted"]["dbTable"] == 3


def test_unversioned_index_is_rebuilt_for_korean_table_and_column_comments_then_reused():
    async def scenario():
        payload = strict_payload()
        payload["sourceSummary"]["changeRequirement"]["title"] = "목표완료일"
        tables = payload["databaseSummary"]["tables"]
        tables[0].update(comment="목표완료일 관리 정보", columns=[{"name": "record_id"}])
        tables[1]["columns"] = [{"name": "detail_value", "comment": "승인사유 기록"}]
        before = copy.deepcopy(payload)
        source = {key: value for key, value in payload["sourceSummary"].items()
                  if key not in {"changeRequirement", "promptCoverage", "analysisScope"}}
        database = {key: value for key, value in payload["databaseSummary"].items() if key != "capturedAt"}
        legacy_key = hashlib.sha256(json.dumps(
            {"s": source, "d": database, "r": payload["relations"]},
            ensure_ascii=False, sort_keys=True, separators=(",", ":"),
        ).encode("utf-8")).hexdigest()
        legacy_chunks = build_chunks(payload)
        for chunk in legacy_chunks:
            if chunk["kind"] == "dbTable":
                table = chunk["item"]
                chunk["text"] = f"{table['schema']}.{table['name']} " + " ".join(
                    column["name"] for column in table.get("columns", []))
        store = MemoryStore()
        store.save(payload["projectId"], legacy_key, legacy_chunks, None, "")
        initial_saves = store.saves
        assert all("목표완료일" not in chunk["text"] and "승인사유" not in chunk["text"]
                   for chunk in store.load(payload["projectId"], legacy_key))
        retriever = EvidenceRetriever(store)

        first = await retriever.retrieve(payload)

        new_key = evidence_key(payload)
        assert new_key != legacy_key
        assert store.saves == initial_saves + 1
        assert not store.has(payload["projectId"], legacy_key)
        assert store.has(payload["projectId"], new_key)
        assert [table["name"] for table in first["databaseSummary"]["tables"]] == ["item_0"]
        cached = store.load(payload["projectId"], new_key)
        assert any("목표완료일" in chunk["text"] for chunk in cached)
        assert any("승인사유" in chunk["text"] for chunk in cached)

        changed = copy.deepcopy(payload)
        changed["sourceSummary"]["changeRequirement"]["title"] = "승인사유"
        second = await retriever.retrieve(changed)

        assert evidence_key(changed) == new_key
        assert [table["name"] for table in second["databaseSummary"]["tables"]] == ["item_1"]
        assert store.saves == initial_saves + 1
        assert payload == before
        assert changed["sourceSummary"]["changeRequirement"]["evidenceSelection"] == "ALWAYS"

    asyncio.run(scenario())
