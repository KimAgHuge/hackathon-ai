import copy
import json

import pytest

from app.services.database_facts import build_database_facts


def table(number=0):
    return {
        "schema": "sample", "name": f"items_{number}",
        "columns": [{"name": "id", "type": "integer", "nullable": False},
                    {"name": "payload", "type": "jsonb", "nullable": True}],
        "primaryKey": ["id"],
        "foreignKeys": [{"column": "parent_id", "referencedSchema": "sample",
                         "referencedTable": "parents", "referencedColumn": "id"}],
        "indexes": [{"name": f"key_{number}", "columns": ["id"], "unique": True},
                    {"name": f"search_{number}", "columns": ["payload", "id"], "unique": False}],
    }


def size(value):
    return len(json.dumps(value, ensure_ascii=False, separators=(",", ":")))


@pytest.mark.parametrize("count", [0, 1, 2, 7, 19])
def test_arbitrary_table_counts_preserve_all_observed_metadata(count):
    originals = [table(number) for number in range(count)]
    before = copy.deepcopy(originals)
    facts = build_database_facts(originals, max_chars=200000, max_tables=count)
    assert facts["tablesAvailable"] is True and facts["collectedTableCount"] == count
    assert len(facts["tables"]) == count and facts["omittedTables"] == []
    assert facts["truncated"] is False and facts["budgetExceeded"] is False
    for result, original in zip(facts["tables"], originals):
        assert result["table"] == f"sample.{original['name']}"
        assert result["columns"] == original["columns"] and result["collectedColumnCount"] == 2
        assert result["indexes"] == original["indexes"] and result["collectedIndexCount"] == 2
        assert result["foreignKeys"] == original["foreignKeys"] and result["collectedForeignKeyCount"] == 1
        assert result["primaryKey"] == original["primaryKey"] and result["collectedPrimaryKeyColumnCount"] == 1
        assert result["allCollectedColumnsIncluded"] is True
        assert result["allCollectedIndexesIncluded"] is True
        assert result["allCollectedForeignKeysIncluded"] is True
        assert result["allCollectedPrimaryKeyColumnsIncluded"] is True
    assert originals == before


@pytest.mark.parametrize("field,available,count,included", [
    ("columns", "columnsAvailable", "collectedColumnCount", "allCollectedColumnsIncluded"),
    ("indexes", "indexesAvailable", "collectedIndexCount", "allCollectedIndexesIncluded"),
    ("foreignKeys", "foreignKeysAvailable", "collectedForeignKeyCount", "allCollectedForeignKeysIncluded"),
    ("primaryKey", "primaryKeyAvailable", "collectedPrimaryKeyColumnCount", "allCollectedPrimaryKeyColumnsIncluded"),
])
def test_missing_and_observed_empty_lists_have_distinct_availability(field, available, count, included):
    original = table()
    for value in [None, "unknown", {}]:
        original[field] = value
        facts = build_database_facts([original])["tables"][0]
        assert facts[available] is False and facts[count] is None and facts[included] is False
    original.pop(field)
    assert build_database_facts([original])["tables"][0][available] is False
    original[field] = []
    facts = build_database_facts([original])["tables"][0]
    assert facts[available] is True and facts[count] == 0 and facts[included] is True


def test_index_attributes_are_preserved_without_guessing_unknown_or_coercing_uniqueness():
    original = table()
    original["indexes"] = [
        {"name": "unique_idx", "columns": ["id"], "unique": True},
        {"name": "ordinary_idx", "columns": ["payload", "id"], "unique": False},
        {"name": "unknown_idx"},
        {"name": "invalid_idx", "columns": ["id", 7], "unique": "false"},
        {"columns": [], "unique": 0},
    ]
    facts = build_database_facts([original])["tables"][0]
    assert facts["collectedIndexCount"] == 5 and facts["allCollectedIndexesIncluded"] is True
    assert facts["indexes"] == [
        {"name": "unique_idx", "columns": ["id"], "unique": True},
        {"name": "ordinary_idx", "columns": ["payload", "id"], "unique": False},
        {"name": "unknown_idx", "columns": None, "unique": None},
        {"name": "invalid_idx", "columns": None, "unique": None},
        {"name": None, "columns": [], "unique": None},
    ]


@pytest.mark.parametrize("kind", ["json", " JSONB ", "Json(100)", "JSON ( 2048 )", "other.JSONB", "other . json"])
def test_json_types_only_identify_unverified_internal_keys_and_row_values(kind):
    original = table()
    original["columns"][1]["type"] = kind
    facts = build_database_facts([original])
    result = facts["tables"][0]
    assert result["jsonColumns"] == [{"column": "payload", "internalKeys": "UNVERIFIED", "rowValues": "UNVERIFIED"}]
    assert result["columns"][1]["type"] == kind
    assert result["collectedJsonColumnCount"] == 1
    for key in ["jsonInternalKeysVerified", "rowValuesVerified", "runtimeUsageVerified", "performanceVerified"]:
        assert facts[key] is False
    assert result["jsonInternalKeysVerified"] is False and result["rowValuesVerified"] is False


@pytest.mark.parametrize("kind", ["varchar", "json_document", "myjson", "jsonb_not_null", None, 17])
def test_unrecognized_column_types_do_not_invent_json_observations(kind):
    original = table()
    original["columns"][1]["type"] = kind
    result = build_database_facts([original])["tables"][0]
    assert result["jsonColumns"] == [] and result["collectedJsonColumnCount"] == 0


def test_merge_can_omit_column_details_without_losing_counts_or_json_uncertainty():
    facts = build_database_facts([table()], include_columns=False)
    result = facts["tables"][0]
    assert result["columns"] == [] and result["columnsAvailable"] is True
    assert result["collectedColumnCount"] == 2 and result["omittedColumnCount"] == 2
    assert result["allCollectedColumnsIncluded"] is False and facts["truncated"] is True
    assert result["indexes"] == table()["indexes"] and result["allCollectedIndexesIncluded"] is True
    assert result["jsonColumns"][0]["internalKeys"] == "UNVERIFIED"
    empty = table()
    empty["columns"] = []
    assert build_database_facts([empty], include_columns=False)["tables"][0]["allCollectedColumnsIncluded"] is True


def test_merge_can_omit_relationship_details_when_the_same_facts_are_supplied_separately():
    original = table()
    before = copy.deepcopy(original)
    facts = build_database_facts([original], include_columns=False, include_relationships=False)
    result = facts["tables"][0]
    assert not any("primarykey" in key.lower() or "foreignkey" in key.lower() for key in result)
    assert result["indexes"] == original["indexes"] and result["allCollectedIndexesIncluded"] is True
    assert result["columnsAvailable"] is True and result["collectedColumnCount"] == 2
    assert result["jsonColumns"][0]["internalKeys"] == "UNVERIFIED"
    assert facts["relationshipDetailsOmitted"] is True and facts["truncated"] is True
    assert original == before
    default = build_database_facts([original])["tables"][0]
    assert default["primaryKey"] == original["primaryKey"]
    assert default["foreignKeys"] == original["foreignKeys"]
    assert default["allCollectedForeignKeysIncluded"] is True


@pytest.mark.parametrize("budget", [1000, 1800, 4000])
def test_budgets_do_not_require_omitted_relationship_fields(budget):
    originals = [table(number) for number in range(7)]
    facts = build_database_facts(originals, max_chars=budget, max_tables=7, include_relationships=False)
    assert size(facts) <= budget and facts["budgetExceeded"] is False
    assert facts["collectedTableCount"] == 7
    assert len(facts["tables"]) + len(facts["omittedTables"]) == 7
    for result in facts["tables"]:
        assert "foreignKeys" not in result and "primaryKey" not in result
        assert "allCollectedForeignKeysIncluded" not in result and "collectedPrimaryKeyColumnCount" not in result
        assert result["omittedIndexCount"] == result["collectedIndexCount"] - len(result["indexes"])


def test_relationship_omission_does_not_imply_relationship_absence_for_missing_or_empty_tables():
    originals = [{"schema": "sample", "name": "unknown"},
                 {"schema": "sample", "name": "empty", "primaryKey": [], "foreignKeys": []}]
    facts = build_database_facts(originals, include_relationships=False)
    assert facts["relationshipDetailsOmitted"] is True
    assert all("primaryKeyAvailable" not in result and "foreignKeysAvailable" not in result for result in facts["tables"])


@pytest.mark.parametrize("budget", [1200, 1800, 3400, 6000, 12000])
def test_bounded_payloads_keep_counts_and_explicitly_report_each_omission(budget):
    originals = [table(number) for number in range(7)]
    for original in originals:
        original["columns"] += [{"name": f"value_{number}", "type": "text"} for number in range(70)]
        original["indexes"] += [{"name": f"long_index_{number}", "columns": ["x" * 150], "unique": False} for number in range(7)]
    before = copy.deepcopy(originals)
    facts = build_database_facts(originals, max_chars=budget, max_tables=7)
    assert size(facts) <= budget and facts["budgetExceeded"] is False and facts["truncated"] is True
    assert facts["collectedTableCount"] == 7
    assert len(facts["tables"]) + len(facts["omittedTables"]) == 7
    for result in facts["tables"]:
        for values, count, omitted, included in [
            ("columns", "collectedColumnCount", "omittedColumnCount", "allCollectedColumnsIncluded"),
            ("indexes", "collectedIndexCount", "omittedIndexCount", "allCollectedIndexesIncluded"),
            ("foreignKeys", "collectedForeignKeyCount", "omittedForeignKeyCount", "allCollectedForeignKeysIncluded"),
            ("primaryKey", "collectedPrimaryKeyColumnCount", "omittedPrimaryKeyColumnCount", "allCollectedPrimaryKeyColumnsIncluded"),
        ]:
            assert result[omitted] == result[count] - len(result[values] or [])
            assert result[included] == (result[omitted] == 0)
        assert result["collectedColumnCount"] == 72 and result["collectedIndexCount"] == 9
    assert originals == before


def test_maximum_tables_and_unfit_minimum_metadata_are_explicit():
    originals = [table(number) for number in range(19)]
    limited = build_database_facts(originals, max_chars=50000, max_tables=2)
    assert len(limited["tables"]) == 2 and limited["collectedTableCount"] == 19
    assert limited["omittedTables"] == [f"sample.items_{number}" for number in range(2, 19)]
    assert limited["truncated"] is True
    tiny = build_database_facts(originals, max_chars=1, max_tables=0)
    assert tiny["tables"] == [] and len(tiny["omittedTables"]) == 19
    assert tiny["collectedTableCount"] == 19 and tiny["budgetExceeded"] is True


def test_output_and_context_are_independent_copies_of_the_inputs():
    originals = [table()]
    context = {"sourceCoverage": {"warnings": ["수집 범위 확인"]}}
    original_copy, context_copy = copy.deepcopy(originals), copy.deepcopy(context)
    facts = build_database_facts(originals, context=context)
    facts["tables"][0]["indexes"][1]["columns"].append("invented")
    facts["tables"][0]["foreignKeys"][0]["column"] = "invented"
    facts["tables"][0]["primaryKey"].append("invented")
    facts["tables"][0]["columns"][0]["name"] = "invented"
    facts["sourceCoverage"]["warnings"].append("다른 경고")
    assert originals == original_copy and context == context_copy


def test_missing_table_metadata_and_malformed_entries_are_not_an_observed_empty_database():
    for value in [None, {}, "unknown"]:
        facts = build_database_facts(value)
        assert facts["tablesAvailable"] is False and facts["collectedTableCount"] is None
    empty = build_database_facts([])
    assert empty["tablesAvailable"] is True and empty["collectedTableCount"] == 0
    malformed = build_database_facts([None, {}, {"name": "known", "columns": [None, {"name": "id"}]}])
    assert malformed["collectedTableCount"] == 3 and malformed["unidentifiedTableCount"] == 2
    assert malformed["truncated"] is True
    assert malformed["tables"][0]["table"] == "known"
    assert malformed["tables"][0]["collectedColumnCount"] == 2
    assert malformed["tables"][0]["omittedColumnCount"] == 1


@pytest.mark.parametrize("argument,value", [("max_chars", -1), ("max_chars", True), ("max_tables", -1), ("max_tables", 1.5)])
def test_invalid_budgets_are_rejected(argument, value):
    with pytest.raises(ValueError, match="문자 예산"):
        build_database_facts([], **{argument: value})
