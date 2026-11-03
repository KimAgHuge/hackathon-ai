import asyncio
import json
from types import SimpleNamespace

import pytest

from app.models.analysis import RequirementGeneralAnalysisResponse
from app.services.llm_service import InvalidModelResponseError, LlmService
from app.services.source_context import evidence_references
from tests.test_evidence_constraints import file_branches
from tests.test_llm_service import (
    FakeClient,
    REQUIREMENT_GENERAL_MODEL_JSON,
    requirement_request,
    settings,
)


@pytest.mark.parametrize("invalid", [
    {"file": "databaseSummary", "line": None},
    {"file": "src/ProjectStore.java", "line": 999},
    {"file": "src/ProjectStore.java", "line": "3"},
    {"file": "src/ProjectStore.java", "line": True},
])
def test_general_evidence_repair_is_scoped_and_cannot_change_the_analysis(invalid):
    request = requirement_request()
    overview = LlmService._general_overview(request)
    references = evidence_references(overview, overview["sourceSummary"]["sourceContext"])
    initial = {**json.loads(REQUIREMENT_GENERAL_MODEL_JSON), "evidences": [invalid]}
    valid = {"file": "src/ProjectStore.java", "line": 3, "description": "수집한 저장 대상 선언을 확인했습니다."}
    client = FakeClient(chat_responses=[json.dumps(initial), json.dumps({"evidences": [valid]})])
    service = LlmService(settings(ai_requirement_fast_path_enabled=True), client=client)

    result = asyncio.run(service._generate(
        [{"role": "user", "content": "전달한 소스 근거로 분석하세요."}], RequirementGeneralAnalysisResponse,
        validate=lambda item: service._validate_evidence(overview, item, context=overview["sourceSummary"]["sourceContext"]),
        evidence_refs=(reference for reference in references),
    ))

    assert result.evidences == [valid]
    assert result.summary == initial["summary"]
    assert client.chat_calls == 2
    full, repair = [call["format"] for call in client.chat_kwargs]
    assert set(repair["properties"]) == {"evidences"}
    assert file_branches(full) == file_branches(repair)
    assert file_branches(repair)["src/ProjectStore.java"]["properties"]["line"]["enum"] == [None, 3]


def test_ignoring_generation_constraints_still_fails_server_validation():
    invalid = {**json.loads(REQUIREMENT_GENERAL_MODEL_JSON),
               "evidences": [{"file": "invented/store.go", "line": 12}]}
    client = FakeClient(chat_responses=[json.dumps(invalid), json.dumps({"evidences": invalid["evidences"]})])
    with pytest.raises(InvalidModelResponseError):
        asyncio.run(LlmService(settings(ai_requirement_fast_path_enabled=True), client=client).analyze(requirement_request()))
    assert client.chat_calls == 2
    full, repair = [call["format"] for call in client.chat_kwargs]
    assert "src/ProjectStore.java" in file_branches(full)
    assert file_branches(full) == file_branches(repair)


@pytest.mark.parametrize("line", [None, 3])
def test_known_file_evidence_keeps_existing_file_and_line_policy(line):
    request = requirement_request()
    overview = LlmService._general_overview(request)
    LlmService._validate_evidence(overview, SimpleNamespace(evidences=[{"file": "src/ProjectStore.java", "line": line}]),
                                  context=overview["sourceSummary"]["sourceContext"])
