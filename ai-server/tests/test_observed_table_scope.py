import asyncio
import copy
import json
import logging

import pytest

from app.models.analysis import DatabaseImpact
from app.services.llm_service import InvalidModelResponseError, LlmService
from app.services.response_repairs import restrict_observed_tables
from app.services.stage_checkpoints import StageCheckpoints
from tests.test_database_batches import NO_MEMBER_CHANGE, checkpoint_client
from tests.test_llm_service import (CONCRETE_DB_JSON, REQUIREMENT_GENERAL_MODEL_JSON,
                                    FakeClient, db_only, requirement_request, settings)


UNKNOWN = json.dumps({"status": "UNKNOWN", "explanation": "이번 범위만으로 업무 대상의 변경을 확정할 수 없습니다.",
                      "observedTables": [], "proposedChanges": [], "conditionalPaths": [], "openQuestions": []})


def enum(call):
    return call["format"]["properties"]["observedTables"]["items"]["enum"]


def test_schema_restricts_only_observed_names_without_changing_the_shared_contract():
    schema = DatabaseImpact.model_json_schema(by_alias=True)
    before = copy.deepcopy(schema)
    scoped = restrict_observed_tables(schema, {"example.table_b", "example.table_a"})
    assert scoped["properties"]["observedTables"]["items"]["enum"] == ["example.table_a", "example.table_b"]
    assert scoped["$defs"] == schema["$defs"]
    assert "enum" not in scoped["$defs"]["DatabaseChange"]["properties"]["targetTable"]
    assert schema == before
    other = restrict_observed_tables(schema, {"example.table_c"})
    assert other["properties"]["observedTables"]["items"]["enum"] == ["example.table_c"]
    assert scoped["properties"]["observedTables"]["items"]["enum"] == ["example.table_a", "example.table_b"]


def test_empty_scope_uses_an_empty_array_and_a_patch_without_observations_is_unchanged():
    scoped = restrict_observed_tables(DatabaseImpact.model_json_schema(by_alias=True), set())
    assert scoped["properties"]["observedTables"]["maxItems"] == 0
    assert "enum" not in scoped["properties"]["observedTables"]["items"]
    patch = {"type": "object", "properties": {"explanation": {"type": "string"}}}
    assert restrict_observed_tables(patch, {"example.table_a"}) == patch


def test_other_batch_observation_repair_keeps_its_own_enum_and_preserves_the_db_decision(caplog):
    request = requirement_request()
    before = request.model_dump(mode="json", by_alias=True)
    wrong_member = {**json.loads(NO_MEMBER_CHANGE), "observedTables": ["public.projects"]}
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, db_only(CONCRETE_DB_JSON),
                                        json.dumps(wrong_member), json.dumps({"observedTables": ["public.project_members"]}),
                                        db_only(CONCRETE_DB_JSON)])
    with caplog.at_level(logging.INFO):
        result = asyncio.run(LlmService(settings(ai_db_batch_max_tables=1, ai_requirement_fast_path_enabled=True), client=client)
                             .analyze(request))
    assert result.database_impact.status.value == "CHANGE_REQUIRED"
    assert client.chat_calls == 5
    assert enum(client.chat_kwargs[1]) == ["public.projects"]
    assert enum(client.chat_kwargs[2]) == enum(client.chat_kwargs[3]) == ["public.project_members"]
    assert set(client.chat_kwargs[3]["format"]["properties"]) == {"observedTables"}
    assert enum(client.chat_kwargs[4]) == ["public.project_members", "public.projects"]
    assert "DB_OBSERVED_TABLE_OUTSIDE_SCOPE" in caplog.text
    assert "observed_table_count=1" in caplog.text
    assert all("think" not in call and call["options"] == {"temperature": 0} for call in client.chat_kwargs)
    assert request.model_dump(mode="json", by_alias=True) == before


def test_schema_ignoring_responses_are_still_rejected_and_never_saved(tmp_path):
    store = StageCheckpoints(tmp_path / "stages", "test-only-signing-key")
    client = checkpoint_client([REQUIREMENT_GENERAL_MODEL_JSON, NO_MEMBER_CHANGE,
                                json.dumps({"observedTables": ["public.project_members"]})])
    with pytest.raises(InvalidModelResponseError):
        asyncio.run(LlmService(settings(ai_db_batch_max_tables=1, ai_requirement_fast_path_enabled=True), client=client, checkpoints=store)
                    .analyze(requirement_request()))
    assert enum(client.chat_kwargs[1]) == enum(client.chat_kwargs[2]) == ["public.projects"]
    assert len(list(store.directory.glob("*.json"))) == 1


def test_full_json_retry_does_not_lose_the_observed_table_enum():
    client = FakeClient(chat_responses=["not-json", db_only(CONCRETE_DB_JSON)])
    result = asyncio.run(LlmService(settings(ai_requirement_fast_path_enabled=True), client=client)._generate(
        [{"role": "user", "content": "이번 묶음의 DB 구조를 검토해 주세요."}], DatabaseImpact,
        observed_tables={"public.projects"}))
    assert result.status.value == "CHANGE_REQUIRED"
    assert enum(client.chat_kwargs[0]) == enum(client.chat_kwargs[1]) == ["public.projects"]
    assert "proposedChanges" in client.chat_kwargs[1]["format"]["properties"]


def test_single_database_call_and_empty_metadata_are_also_scoped():
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, db_only(CONCRETE_DB_JSON)])
    asyncio.run(LlmService(settings(), client=client).analyze(requirement_request()))
    assert enum(client.chat_kwargs[1]) == ["public.project_members", "public.projects"]
    empty_client = FakeClient(chat_responses=[UNKNOWN])
    asyncio.run(LlmService(settings(), client=empty_client)._generate(
        [{"role": "user", "content": "수집된 테이블이 없습니다."}], DatabaseImpact, observed_tables=set()))
    assert empty_client.chat_kwargs[0]["format"]["properties"]["observedTables"]["maxItems"] == 0


def test_intermediate_merges_cannot_observe_unreviewed_groups():
    request = requirement_request()
    request.database_summary["tables"] = [{"schema": "example", "name": f"table_{index}", "columns": []} for index in range(5)]
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, *[UNKNOWN] * 7])
    asyncio.run(LlmService(settings(ai_db_batch_max_tables=1), client=client).analyze(request))
    for call in client.chat_kwargs[1:]:
        payload = json.loads(call["messages"][1]["content"])
        expected = payload["coverage"]["reviewedTables"] if "reviews" in payload else payload["batchScope"]["detailedTables"]
        assert enum(call) == sorted(expected)
    assert len(enum(client.chat_kwargs[-2])) == 4
    assert len(enum(client.chat_kwargs[-1])) == 5


def test_checkpoint_identity_includes_the_constrained_schema(tmp_path):
    store = StageCheckpoints(tmp_path / "stages", "test-only-signing-key")
    messages = [{"role": "user", "content": "동일한 자료에서 검토 범위만 다르게 지정하는 시험입니다."}]
    for tables, expected_calls in [({"example.table_a"}, 1), ({"example.table_b"}, 1), ({"example.table_a"}, 0)]:
        client = checkpoint_client([UNKNOWN])
        asyncio.run(LlmService(settings(), client=client, checkpoints=store)._generate(
            messages, DatabaseImpact, observed_tables=tables, checkpoint_scope="same-request"))
        assert client.chat_calls == expected_calls
    assert len(list(store.directory.glob("*.json"))) == 2
