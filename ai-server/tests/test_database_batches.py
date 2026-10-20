import asyncio
import json
from uuid import UUID

import httpx
import pytest

from app.services.llm_service import InvalidModelResponseError, LlmService, ModelTimeoutError
from app.services.stage_checkpoints import StageCheckpoints
from tests.test_llm_service import FakeClient, REQUIREMENT_GENERAL_MODEL_JSON, CONCRETE_DB_JSON, db_only, requirement_request, settings


NO_MEMBER_CHANGE = json.dumps({
    "status": "NO_CHANGE", "explanation": "이번 묶음의 구성원 연결에는 변경이 필요하지 않습니다.",
    "observedTables": ["public.project_members"], "proposedChanges": [], "conditionalPaths": [], "openQuestions": [],
}, ensure_ascii=False)


def checkpoint_client(responses):
    return FakeClient(chat_responses=responses, list_response={"models": [{"model": settings().ai_model, "digest": "test-model-digest"}]})


def test_every_table_is_reviewed_without_disabling_thinking_and_cross_table_keys_reach_merge():
    request = requirement_request()
    request.database_summary["tables"][1].update({
        "primaryKey": ["user_id"],
        "foreignKeys": [{"column": "project_id", "referencedTable": "projects", "referencedSchema": "public"}],
        "indexes": [{"name": "index_example", "unique": True, "columns": ["user_id"]}],
    })
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, db_only(CONCRETE_DB_JSON), NO_MEMBER_CHANGE, db_only(CONCRETE_DB_JSON)])
    events = []

    async def progress(code, number):
        events.append((code, number))

    result = asyncio.run(LlmService(settings(ai_db_batch_max_tables=1), client=client).analyze(request, progress))
    assert result.database_impact.status.value == "CHANGE_REQUIRED"
    parts = [json.loads(call["messages"][1]["content"]) for call in client.chat_kwargs[1:3]]
    assert [table["name"] for part in parts for table in part["tables"]] == ["projects", "project_members"]
    assert parts[1]["tables"][0]["foreignKeys"] == request.database_summary["tables"][1]["foreignKeys"]
    assert parts[1]["tables"][0]["indexes"] == request.database_summary["tables"][1]["indexes"]
    merge = json.loads(client.chat_kwargs[3]["messages"][1]["content"])
    assert merge["observedConstraints"][1]["uniqueIndexes"][0]["name"] == "index_example"
    assert merge["coverage"]["reviewedTables"] == ["public.project_members", "public.projects"]
    assert ("DATABASE_BATCH_STARTED", 1) in events
    assert ("DATABASE_BATCH_COMPLETED", 2) in events
    assert ("DATABASE_MERGE_COMPLETED", 1) in events
    for call in client.chat_kwargs:
        assert "think" not in call
        assert call["options"] == {"temperature": 0}


def test_retry_after_timeout_reuses_general_and_completed_batch_across_service_restart(tmp_path):
    class TimeoutClient(FakeClient):
        async def chat(self, **kwargs):
            if self.chat_calls == 2:
                raise httpx.ReadTimeout("가상 시간 초과")
            return await super().chat(**kwargs)

    store = StageCheckpoints(tmp_path / "stages", "test-only-signing-key")
    initial = TimeoutClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, db_only(CONCRETE_DB_JSON)],
                            list_response=checkpoint_client([]).list_response)
    config = settings(ai_db_batch_max_tables=1)
    request = requirement_request()
    request.database_summary["capturedAt"] = "2026-10-20T00:00:00Z"
    with pytest.raises(ModelTimeoutError):
        asyncio.run(LlmService(config, client=initial, checkpoints=store).analyze(request))
    assert len(list(store.directory.glob("*.json"))) == 2
    resumed = checkpoint_client([NO_MEMBER_CHANGE, db_only(CONCRETE_DB_JSON)])
    request.database_summary["capturedAt"] = "2026-10-20T01:00:00Z"
    events = []

    async def progress(code, number):
        events.append((code, number))

    result = asyncio.run(LlmService(config, client=resumed, checkpoints=StageCheckpoints(store.directory, "test-only-signing-key"))
                         .analyze(request, progress))
    assert result.database_impact.status.value == "CHANGE_REQUIRED"
    assert resumed.chat_calls == 2
    assert ("GENERAL_REUSED", None) in events
    assert ("DATABASE_BATCH_REUSED", 1) in events


@pytest.mark.parametrize("change", ["requirement", "database", "model"])
def test_changed_requirement_schema_or_model_does_not_reuse_completed_general(tmp_path, change):
    store = StageCheckpoints(tmp_path / "stages", "test-only-signing-key")
    first = checkpoint_client([REQUIREMENT_GENERAL_MODEL_JSON, db_only(CONCRETE_DB_JSON)])
    asyncio.run(LlmService(settings(), client=first, checkpoints=store).analyze(requirement_request()))
    request = requirement_request()
    second = checkpoint_client([REQUIREMENT_GENERAL_MODEL_JSON, db_only(CONCRETE_DB_JSON)])
    if change == "requirement":
        request.source_summary["changeRequirement"]["refinedContent"] += " 추가 확인 사항이 있습니다."
    elif change == "database":
        request.database_summary["tables"][0]["columns"].append({"name": "column_example"})
    else:
        second.list_response["models"][0]["digest"] = "different-model-digest"
    asyncio.run(LlmService(settings(), client=second, checkpoints=store).analyze(request))
    assert second.chat_calls == 2


def test_partial_batch_cannot_cite_or_modify_tables_only_seen_in_other_batches():
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, NO_MEMBER_CHANGE, NO_MEMBER_CHANGE])
    with pytest.raises(InvalidModelResponseError):
        asyncio.run(LlmService(settings(ai_db_batch_max_tables=1), client=client).analyze(requirement_request()))


def test_merge_cannot_turn_a_required_change_into_no_change():
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, db_only(CONCRETE_DB_JSON), NO_MEMBER_CHANGE,
                                        NO_MEMBER_CHANGE, NO_MEMBER_CHANGE])
    with pytest.raises(InvalidModelResponseError):
        asyncio.run(LlmService(settings(ai_db_batch_max_tables=1), client=client).analyze(requirement_request()))


def test_single_large_table_is_not_silently_cut_at_batch_budget():
    request = requirement_request()
    request.database_summary["tables"] = [{"schema": "public", "name": "projects",
                                            "columns": [{"name": f"column_example_{index}", "type": "text"} for index in range(100)]}]
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, db_only(CONCRETE_DB_JSON)])
    asyncio.run(LlmService(settings(ai_db_batch_max_chars=1000), client=client).analyze(request))
    sent = json.loads(client.chat_kwargs[1]["messages"][1]["content"])
    assert len(sent["tables"][0]["columns"]) == 100


def test_multiple_merge_levels_preserve_every_reviewed_table():
    request = requirement_request()
    request.database_summary["tables"] = [{"schema": "example", "name": f"table_example_{index}", "columns": []} for index in range(5)]
    unknown = {"status": "UNKNOWN", "explanation": "수집된 구조만으로 업무 대상의 연결을 확인하지 못했습니다.",
               "observedTables": [], "proposedChanges": [], "conditionalPaths": [], "openQuestions": []}
    client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, *[json.dumps(unknown, ensure_ascii=False)] * 7])
    result = asyncio.run(LlmService(settings(ai_db_batch_max_tables=1), client=client).analyze(request))
    assert result.database_impact.status.value == "UNKNOWN"
    final = json.loads(client.chat_kwargs[-1]["messages"][1]["content"])
    assert final["coverage"]["reviewedTables"] == [f"example.table_example_{index}" for index in range(5)]
    assert all(len(json.loads(call["messages"][1]["content"])["reviews"]) <= 4 for call in client.chat_kwargs[6:])


def test_checkpoint_scope_separates_projects_and_ignores_only_collection_time():
    request = requirement_request()
    original = LlmService._request_scope(request)
    request.database_summary["capturedAt"] = "2026-10-20T00:00:00Z"
    assert LlmService._request_scope(request) == original
    request.project_id = UUID("00000000-0000-0000-0000-000000000001")
    assert LlmService._request_scope(request) != original


def test_batching_preserves_the_input_and_the_final_contract_for_equal_model_decisions():
    """같은 모델 판단을 받았을 때 계약을 보존하는지 검사한다. 실제 모델 답변의 동일성을 뜻하지는 않는다."""
    request = requirement_request()
    original = request.model_dump(mode="json", by_alias=True)
    unsplit_client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, db_only(CONCRETE_DB_JSON)])
    split_client = FakeClient(chat_responses=[REQUIREMENT_GENERAL_MODEL_JSON, db_only(CONCRETE_DB_JSON),
                                             NO_MEMBER_CHANGE, db_only(CONCRETE_DB_JSON)])

    unsplit = asyncio.run(LlmService(settings(), client=unsplit_client).analyze(request))
    split = asyncio.run(LlmService(settings(ai_db_batch_max_tables=1), client=split_client).analyze(request))

    assert split.model_dump(mode="json", by_alias=True) == unsplit.model_dump(mode="json", by_alias=True)
    assert request.model_dump(mode="json", by_alias=True) == original


def test_failed_batch_does_not_modify_the_original_request_or_save_an_incomplete_result(tmp_path):
    store = StageCheckpoints(tmp_path / "stages", "test-only-signing-key")
    request = requirement_request()
    original = request.model_dump(mode="json", by_alias=True)
    client = checkpoint_client([REQUIREMENT_GENERAL_MODEL_JSON, "{}", "{}"])

    with pytest.raises(InvalidModelResponseError):
        asyncio.run(LlmService(settings(ai_db_batch_max_tables=1), client=client, checkpoints=store).analyze(request))

    assert request.model_dump(mode="json", by_alias=True) == original
    assert len(list(store.directory.glob("*.json"))) == 1
