import copy

import pytest

from app.models.analysis import DatabaseImpact, FinalDatabaseFactsReview, GeneralAnalysisResponse
from app.services.database_facts import build_database_facts
from app.services.database_synthesis import DatabaseSynthesis, FinalSynthesisUpdate
from app.services.reconciliation_context import validate_database_facts_review
from app.services.response_repairs import constrain_database_facts, repair_contract


def tables(count):
    return [{
        "schema": "generic", "name": f"items_{number}",
        "columns": [{"name": "record_id", "type": "integer"}, {"name": "payload", "type": "json"}],
        "indexes": [{"name": f"unique_{number}", "columns": ["record_id"], "unique": True},
                    {"name": f"ordinary_{number}", "columns": ["payload", "record_id"], "unique": False}],
    } for number in range(count)]


@pytest.mark.parametrize("count", [0, 1, 2, 7, 19])
@pytest.mark.parametrize("response_type", [DatabaseSynthesis, FinalSynthesisUpdate])
def test_generation_schema_pins_exact_structure_for_any_table_count(count, response_type):
    facts = build_database_facts(tables(count), max_chars=200000, max_tables=count)
    schema = response_type.model_json_schema(by_alias=True)
    before_facts, before_schema = copy.deepcopy(facts), copy.deepcopy(schema)

    constrained = constrain_database_facts(schema, facts)

    property_schema = constrained["properties"]["databaseFactsReview"]
    assert set(property_schema) == {"type", "const"} and property_schema["type"] == "array"
    assert len(property_schema["const"]) == count
    for actual, source in zip(property_schema["const"], facts["tables"]):
        assert set(actual) == {"table", "columnsAvailable", "collectedColumnCount", "includedColumnNames",
                               "allCollectedColumnsIncluded", "indexesAvailable", "collectedIndexCount",
                               "includedIndexes", "allCollectedIndexesIncluded"}
        assert actual["table"] == source["table"]
        assert actual["includedColumnNames"] == [column["name"] for column in source["columns"]]
        assert actual["includedIndexes"] == source["indexes"]
    validate_database_facts_review(facts, [FinalDatabaseFactsReview.model_validate(value) for value in property_schema["const"]])
    assert facts == before_facts and schema == before_schema
    expected_remaining = {key: value for key, value in schema["properties"].items() if key != "databaseFactsReview"}
    actual_remaining = {key: value for key, value in constrained["properties"].items() if key != "databaseFactsReview"}
    assert actual_remaining == expected_remaining


def test_missing_metadata_retains_null_instead_of_inventing_an_observed_empty_list():
    facts = build_database_facts([{"schema": "generic", "name": "unknown"},
                                  {"schema": "generic", "name": "empty", "columns": [], "indexes": []}])
    constrained = constrain_database_facts(DatabaseSynthesis.model_json_schema(by_alias=True), facts)
    unknown, empty = constrained["properties"]["databaseFactsReview"]["const"]
    assert unknown["columnsAvailable"] is False and unknown["collectedColumnCount"] is None
    assert unknown["indexesAvailable"] is False and unknown["collectedIndexCount"] is None
    assert unknown["allCollectedColumnsIncluded"] is False and unknown["allCollectedIndexesIncluded"] is False
    assert empty["columnsAvailable"] is True and empty["collectedColumnCount"] == 0
    assert empty["indexesAvailable"] is True and empty["collectedIndexCount"] == 0
    assert empty["allCollectedColumnsIncluded"] is True and empty["allCollectedIndexesIncluded"] is True


def test_index_boolean_values_unknown_fields_and_composite_order_are_not_coerced():
    originals = tables(1)
    originals[0]["indexes"].append({"name": "unknown"})
    facts = build_database_facts(originals)
    pinned = constrain_database_facts(DatabaseSynthesis.model_json_schema(by_alias=True), facts)
    indexes = pinned["properties"]["databaseFactsReview"]["const"][0]["includedIndexes"]
    assert indexes[0]["unique"] is True and indexes[1]["unique"] is False
    assert indexes[1]["columns"] == ["payload", "record_id"]
    assert indexes[2] == {"name": "unknown", "columns": None, "unique": None}


def test_observed_counts_and_omitted_lists_match_the_bounded_input():
    facts = build_database_facts(tables(7), max_chars=2000, max_tables=7,
                                include_columns=False, include_relationships=False)
    assert facts["truncated"] is True
    constrained = constrain_database_facts(DatabaseSynthesis.model_json_schema(by_alias=True), facts)
    for actual, source in zip(constrained["properties"]["databaseFactsReview"]["const"], facts["tables"]):
        assert actual["collectedColumnCount"] == 2 and actual["includedColumnNames"] == []
        assert actual["allCollectedColumnsIncluded"] is False
        assert actual["collectedIndexCount"] == source["collectedIndexCount"]
        assert actual["allCollectedIndexesIncluded"] == source["allCollectedIndexesIncluded"]
        assert actual["includedIndexes"] == source["indexes"]


def test_old_anyof_references_items_and_descriptions_are_replaced_at_the_property():
    schema = {"type": "object", "properties": {"databaseFactsReview": {
        "anyOf": [{"$ref": "#/$defs/Previous"}], "items": {"type": "string"},
        "description": "이전 생성 지시", "default": ["invented"],
    }}, "$defs": {"Previous": {"type": "array"}}}
    constrained = constrain_database_facts(schema, build_database_facts([]))
    assert constrained["properties"]["databaseFactsReview"] == {"type": "array", "const": []}
    assert schema["properties"]["databaseFactsReview"]["default"] == ["invented"]


@pytest.mark.parametrize("response_type", [DatabaseImpact, GeneralAnalysisResponse])
def test_general_and_partial_database_schemas_remain_identical(response_type):
    schema = response_type.model_json_schema(by_alias=True)
    constrained = constrain_database_facts(schema, build_database_facts(tables(1)))
    assert constrained == schema and constrained is not schema
    assert "databaseFactsReview" not in constrained["properties"]


def test_none_facts_and_schemas_without_the_property_do_not_add_constraints():
    schema = DatabaseSynthesis.model_json_schema(by_alias=True)
    assert constrain_database_facts(schema, None) == schema
    no_properties = {"type": "object", "additionalProperties": False}
    assert constrain_database_facts(no_properties, build_database_facts([])) == no_properties


def test_repair_schema_without_facts_field_never_gets_an_unrequested_field():
    _, schema = repair_contract(FinalSynthesisUpdate, ValueError("검토용 오류"), "FINAL_EVIDENCE_NOT_OBSERVED")
    constrained = constrain_database_facts(schema, build_database_facts(tables(1)))
    assert constrained == schema and set(constrained["properties"]) == {"evidences"}
    assert constrained["required"] == ["evidences"]


def test_facts_repair_schema_has_the_same_const_values_as_the_full_schema():
    facts = build_database_facts(tables(2))
    _, repair = repair_contract(FinalSynthesisUpdate, ValueError("검토용 오류"), "FINAL_DB_FACTS_MISMATCH")
    full = constrain_database_facts(FinalSynthesisUpdate.model_json_schema(by_alias=True), facts)
    constrained_repair = constrain_database_facts(repair, facts)
    assert constrained_repair["properties"]["databaseFactsReview"] == full["properties"]["databaseFactsReview"]
    assert set(constrained_repair["properties"]) == set(repair["properties"])
    assert constrained_repair["required"] == repair["required"]


def test_result_schema_and_facts_are_independent_deep_copies():
    facts = build_database_facts(tables(1))
    schema = DatabaseSynthesis.model_json_schema(by_alias=True)
    before_facts, before_schema = copy.deepcopy(facts), copy.deepcopy(schema)
    constrained = constrain_database_facts(schema, facts)
    constrained["properties"]["databaseFactsReview"]["const"][0]["includedIndexes"][0]["columns"].append("other")
    constrained["$defs"]["DatabaseIndexReview"]["description"] = "출력 사본에서만 수정"
    assert facts == before_facts and schema == before_schema


def test_applying_the_same_constraint_twice_is_stable():
    facts = build_database_facts(tables(1))
    once = constrain_database_facts(DatabaseSynthesis.model_json_schema(by_alias=True), facts)
    assert constrain_database_facts(once, facts) == once
