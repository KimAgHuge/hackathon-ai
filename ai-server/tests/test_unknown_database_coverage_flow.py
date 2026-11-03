import asyncio
import copy

import pytest

from app.services.database_scope import database_coverage, database_input
from app.services.evidence_retriever import EvidenceRetriever
from app.services.llm_service import LlmService
from tests.test_strict_rag_analysis_flow import AnalysisClient, MemoryStore, config, input_payload, request


def assert_coverage(actual, *, truncated, warnings, unknown):
    assert actual["truncated"] is truncated
    assert actual["warnings"] == warnings
    if unknown:
        assert actual["coverageUnknown"] is True
        assert isinstance(actual["notice"], str) and any("가" <= character <= "힣" for character in actual["notice"])
    else:
        assert actual == {"truncated": truncated, "warnings": warnings}


@pytest.mark.parametrize("source,expected_truncated,expected_warnings", [
    ({"truncated": False, "warnings": []}, False, []),
    ({"truncated": True, "warnings": ["일부 DB 구조를 수집하지 못했습니다."]}, True, ["일부 DB 구조를 수집하지 못했습니다."]),
])
def test_known_database_coverage_keeps_the_existing_contract_and_copies_warning_values(source, expected_truncated, expected_warnings):
    before = copy.deepcopy(source)
    result = database_coverage(source)
    assert_coverage(result, truncated=expected_truncated, warnings=expected_warnings, unknown=False)
    result["warnings"].append("출력 사본에서만 추가한 경고입니다.")
    assert source == before


@pytest.mark.parametrize("source,expected_truncated,expected_warnings", [
    ({}, None, None),
    ({"truncated": None, "warnings": None}, None, None),
    ({"truncated": "false", "warnings": "[]"}, None, None),
    ({"truncated": 0, "warnings": {}}, None, None),
    ({"truncated": False}, False, None),
    ({"warnings": []}, None, []),
    ({"truncated": False, "warnings": [], "coverageUnknown": True}, False, []),
])
def test_missing_invalid_or_upstream_unknown_coverage_is_never_invented_as_complete(source, expected_truncated, expected_warnings):
    before = copy.deepcopy(source)
    actual = database_coverage(source)
    assert_coverage(actual, truncated=expected_truncated, warnings=expected_warnings, unknown=True)
    assert source == before


def test_database_input_exposes_unknown_coverage_for_shared_analysis_and_training_messages():
    original = request(0).model_dump(mode="json", by_alias=True)
    before = copy.deepcopy(original)
    details = database_input(original, original["databaseSummary"]["tables"])
    assert_coverage(details["databaseCoverage"], truncated=None, warnings=None, unknown=True)
    assert original == before


@pytest.mark.parametrize("batch_size", [1, 40])
@pytest.mark.parametrize("metadata,expected_truncated,expected_warnings,unknown", [
    ({"truncated": False, "warnings": []}, False, [], False),
    ({"truncated": True, "warnings": ["일부 구조의 수집이 제한됐습니다."]}, True, ["일부 구조의 수집이 제한됐습니다."], False),
    ({}, None, None, True),
    ({"truncated": "false", "warnings": "없음"}, None, None, True),
    ({"truncated": False, "warnings": [], "coverageUnknown": True}, False, [], True),
])
def test_actual_single_batch_merge_and_final_requests_preserve_database_coverage(batch_size, metadata, expected_truncated, expected_warnings, unknown):
    original = request(0, mode="OFF")
    original.database_summary.update(copy.deepcopy(metadata))
    before = original.model_dump(mode="json", by_alias=True)
    client = AnalysisClient()
    result = asyncio.run(LlmService(config(ai_db_batch_max_tables=batch_size), client=client).analyze(original))
    database_calls = [input_payload(call) for call in client.calls if "status" in call["format"]["properties"]]
    assert database_calls
    for payload in database_calls:
        assert_coverage(payload["databaseCoverage"], truncated=expected_truncated, warnings=expected_warnings, unknown=unknown)
    if batch_size == 1:
        assert any("batchScope" in payload for payload in database_calls)
        assert any("reviews" in payload for payload in database_calls)
    else:
        assert len(database_calls) == 1
    final = input_payload(client.calls[-1])
    assert "reconciliationFacts" in final
    assert_coverage(final["reconciliationFacts"]["databaseCoverage"], truncated=expected_truncated, warnings=expected_warnings, unknown=unknown)
    assert result.database_impact.status.value == "UNKNOWN"
    assert original.model_dump(mode="json", by_alias=True) == before


@pytest.mark.parametrize("noise", [0, 20])
@pytest.mark.parametrize("batch_size", [1, 40])
@pytest.mark.parametrize("metadata,expected_warnings", [
    ({}, None),
    ({"truncated": "false", "warnings": "없음"}, None),
    ({"truncated": False, "warnings": [], "coverageUnknown": True}, []),
])
def test_strict_rag_retains_unknown_coverage_even_when_it_adds_known_selection_omissions(noise, batch_size, metadata, expected_warnings):
    original = request(noise)
    original.database_summary.update(copy.deepcopy(metadata))
    before = original.model_dump(mode="json", by_alias=True)
    client = AnalysisClient()
    asyncio.run(LlmService(config(ai_db_batch_max_tables=batch_size), client=client,
                           retriever=EvidenceRetriever(MemoryStore())).analyze(original))
    reduced_general = input_payload(client.calls[0])["databaseSummary"]
    expected_truncated = True if noise else (False if metadata.get("truncated") is False else None)
    assert len(reduced_general["tables"]) == 3
    assert reduced_general.get("coverageUnknown") is True
    assert reduced_general.get("truncated") is expected_truncated
    assert reduced_general.get("warnings") == expected_warnings
    for call in client.calls:
        payload = input_payload(call)
        if "status" in call["format"]["properties"]:
            assert_coverage(payload["databaseCoverage"], truncated=expected_truncated, warnings=expected_warnings, unknown=True)
        if "reconciliationFacts" in payload:
            assert_coverage(payload["reconciliationFacts"]["databaseCoverage"], truncated=expected_truncated, warnings=expected_warnings, unknown=True)
    assert original.model_dump(mode="json", by_alias=True) == before
