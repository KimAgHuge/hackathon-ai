import copy
import json

import pytest

from app.models.analysis import FinalDatabaseFactsReview
from app.services.reconciliation_context import reconciliation_facts, validate_database_facts_review


def inputs():
    request = {"databaseSummary": {"tables": [
        {"schema": "example", "name": "records", "columns": [{"name": "id", "type": "uuid", "nullable": False},
            {"name": "metadata", "type": "jsonb", "nullable": False}], "primaryKey": ["id"], "foreignKeys": [],
         "indexes": [{"name": "index_example", "columns": ["id"], "unique": True}]},
        {"schema": "example", "name": "unrelated", "columns": [{"name": "other_id"}]}]}}
    impact = {"observedTables": ["example.records"], "proposedChanges": []}
    source = {"tableReferences": [], "coverage": {"truncated": True}}
    return request, impact, source


def review(facts):
    return [FinalDatabaseFactsReview.model_validate({"table": table["table"], "columnsAvailable": table["columnsAvailable"],
        "collectedColumnCount": table["collectedColumnCount"], "includedColumnNames": [column["name"] for column in table["columns"]],
        "allCollectedColumnsIncluded": table["allCollectedColumnsIncluded"],
        "indexesAvailable": table["indexesAvailable"], "collectedIndexCount": table["collectedIndexCount"],
        "includedIndexes": table["indexes"], "allCollectedIndexesIncluded": table["allCollectedIndexesIncluded"]}) for table in facts["tables"]]


def test_metadata_is_confirmed_independently_of_source_and_row_data_without_changing_the_input():
    request, impact, source = inputs()
    request["databaseSummary"].update(truncated=True, warnings=["컬럼 수집 한도가 적용됐습니다."])
    before = copy.deepcopy((request, impact, source))
    facts = reconciliation_facts(request, impact, source)
    table = facts["tables"][0]
    assert table["table"] == "example.records" and table["collectedColumnCount"] == 2
    assert table["columns"] == request["databaseSummary"]["tables"][0]["columns"]
    assert table["primaryKey"] == ["id"] and table["indexes"][0]["unique"] is True
    assert table["includedSourceTableReferences"] == 0
    assert facts["sourceCoverage"]["truncated"]
    assert facts["databaseCoverage"] == {"truncated": True, "warnings": ["컬럼 수집 한도가 적용됐습니다."]}
    assert facts["rowValuesVerified"] is False and facts["runtimeUsageVerified"] is False
    assert facts["performanceVerified"] is False and facts["jsonInternalKeysVerified"] is False
    assert table["indexesAvailable"] is True and table["collectedIndexCount"] == 1
    assert table["allCollectedIndexesIncluded"] is True
    assert table["jsonColumns"] == [{"column": "metadata", "internalKeys": "UNVERIFIED", "rowValues": "UNVERIFIED"}]
    assert "example.unrelated" not in json.dumps(facts)
    assert (request, impact, source) == before
    validate_database_facts_review(facts, review(facts))


def test_missing_columns_and_observed_empty_columns_are_not_equivalent():
    request, impact, source = inputs()
    request["databaseSummary"]["tables"][0].pop("columns")
    missing = reconciliation_facts(request, impact, source)
    assert missing["tables"][0]["columnsAvailable"] is False
    assert missing["tables"][0]["collectedColumnCount"] is None
    assert missing["tables"][0]["allCollectedColumnsIncluded"] is False
    request["databaseSummary"]["tables"][0]["columns"] = []
    empty = reconciliation_facts(request, impact, source)
    assert empty["tables"][0]["columnsAvailable"] is True
    assert empty["tables"][0]["collectedColumnCount"] == 0
    assert empty["tables"][0]["allCollectedColumnsIncluded"] is True
    validate_database_facts_review(missing, review(missing))
    validate_database_facts_review(empty, review(empty))


def test_large_metadata_preserves_original_counts_and_reports_omissions():
    request, impact, source = inputs()
    request["databaseSummary"]["tables"][0]["columns"] = [{"name": f"column_example_{index}", "type": "text"} for index in range(100)]
    facts = reconciliation_facts(request, impact, source, max_chars=1600)
    assert len(json.dumps(facts, ensure_ascii=False, separators=(",", ":"))) <= 1600
    assert facts["tables"][0]["collectedColumnCount"] == 100
    assert len(facts["tables"][0]["columns"]) < 100
    assert facts["tables"][0]["allCollectedColumnsIncluded"] is False
    assert len(request["databaseSummary"]["tables"][0]["columns"]) == 100
    validate_database_facts_review(facts, review(facts))


def test_changed_targets_are_prioritized_and_uncollected_proposals_are_not_invented_as_observations():
    request, impact, source = inputs()
    impact["observedTables"].append("example.unrelated")
    impact["proposedChanges"] = [{"targetTable": "example.new_records"}, {"targetTable": "example.unrelated"}]
    facts = reconciliation_facts(request, impact, source, max_tables=1)
    assert [table["table"] for table in facts["tables"]] == ["example.unrelated"]
    assert facts["omittedTables"] == ["example.records"]
    assert facts["targetsNotInCollectedMetadata"] == ["example.new_records"]


@pytest.mark.parametrize("change", ["count", "names", "duplicate_name", "availability", "coverage", "missing", "duplicate_table", "unknown_table"])
def test_incorrect_structural_confirmation_is_rejected(change):
    facts = reconciliation_facts(*inputs())
    values = review(facts)
    if change == "count":
        values[0].collected_column_count = 11
    elif change == "names":
        values[0].included_column_names = ["invented"]
    elif change == "duplicate_name":
        values[0].included_column_names = ["id", "id", "metadata"]
    elif change == "availability":
        values[0].columns_available = False
    elif change == "coverage":
        values[0].all_collected_columns_included = False
    elif change == "missing":
        values = []
    elif change == "duplicate_table":
        values.append(values[0])
    else:
        values[0].table = "example.invented"
    with pytest.raises(ValueError, match="DB 구조 확인값"):
        validate_database_facts_review(facts, values)


def test_indexes_missing_empty_and_unknown_uniqueness_are_confirmed_independently():
    request, impact, source = inputs()
    original = request["databaseSummary"]["tables"][0]
    original.pop("indexes")
    unknown = reconciliation_facts(request, impact, source)
    assert unknown["tables"][0]["indexesAvailable"] is False
    assert unknown["tables"][0]["collectedIndexCount"] is None
    assert unknown["tables"][0]["allCollectedIndexesIncluded"] is False
    validate_database_facts_review(unknown, review(unknown))
    original["indexes"] = []
    empty = reconciliation_facts(request, impact, source)
    assert empty["tables"][0]["indexesAvailable"] is True
    assert empty["tables"][0]["collectedIndexCount"] == 0
    assert empty["tables"][0]["allCollectedIndexesIncluded"] is True
    validate_database_facts_review(empty, review(empty))
    original["indexes"] = [{"name": "ordinary", "columns": ["id"], "unique": False}, {"name": "unknown"}]
    mixed = reconciliation_facts(request, impact, source)
    assert mixed["tables"][0]["indexes"] == [
        {"name": "ordinary", "columns": ["id"], "unique": False},
        {"name": "unknown", "columns": None, "unique": None},
    ]
    validate_database_facts_review(mixed, review(mixed))


@pytest.mark.parametrize("change", ["count", "availability", "coverage", "names", "columns", "unique", "missing", "duplicate"])
def test_incorrect_index_confirmation_is_rejected(change):
    facts = reconciliation_facts(*inputs())
    values = review(facts)
    if change == "count":
        values[0].collected_index_count = 0
    elif change == "availability":
        values[0].indexes_available = False
    elif change == "coverage":
        values[0].all_collected_indexes_included = False
    elif change == "names":
        values[0].included_indexes[0].name = "invented"
    elif change == "columns":
        values[0].included_indexes[0].columns = ["invented"]
    elif change == "unique":
        values[0].included_indexes[0].unique = False
    elif change == "missing":
        values[0].included_indexes = []
    else:
        values[0].included_indexes.append(values[0].included_indexes[0])
    with pytest.raises(ValueError, match="DB 구조 확인값"):
        validate_database_facts_review(facts, values)


def test_index_list_order_is_not_a_fact_but_composite_column_order_is():
    request, impact, source = inputs()
    request["databaseSummary"]["tables"][0]["indexes"] = [
        {"name": "first", "columns": ["id", "metadata"], "unique": True},
        {"name": "second", "columns": ["metadata"], "unique": False},
    ]
    facts = reconciliation_facts(request, impact, source)
    values = review(facts)
    values[0].included_indexes.reverse()
    validate_database_facts_review(facts, values)
    values[0].included_indexes[1].columns.reverse()
    with pytest.raises(ValueError, match="DB 구조 확인값"):
        validate_database_facts_review(facts, values)


def test_context_is_included_in_budget_and_an_unfit_minimum_is_explicit():
    request, impact, source = inputs()
    source["coverage"]["note"] = "관찰 범위" * 300
    facts = reconciliation_facts(request, impact, source, max_chars=300)
    assert facts["budgetExceeded"] is True
    assert facts["sourceCoverage"] == source["coverage"]
    assert facts["tables"] == [] and facts["omittedTables"] == ["example.records"]


@pytest.mark.parametrize("attribute,value,field", [
    ("columns_available", False, "columnsAvailable"),
    ("collected_column_count", 999, "collectedColumnCount"),
    ("included_column_names", ["fake-secret-column"], "includedColumnNames"),
    ("all_collected_columns_included", False, "allCollectedColumnsIncluded"),
    ("indexes_available", False, "indexesAvailable"),
    ("collected_index_count", 999, "collectedIndexCount"),
    ("included_indexes", [], "includedIndexes"),
    ("all_collected_indexes_included", False, "allCollectedIndexesIncluded"),
])
def test_structural_feedback_identifies_only_the_fixed_field_and_safe_table_number(attribute, value, field):
    request, impact, source = inputs()
    original = request["databaseSummary"]["tables"][0]
    original["schema"] = "fake_secret_schema"
    original["name"] = "fake_secret_table"
    original["columns"][0]["name"] = "fake_secret_column"
    original["indexes"][0]["name"] = "fake_secret_index"
    impact["observedTables"] = ["fake_secret_schema.fake_secret_table"]
    facts = reconciliation_facts(request, impact, source)
    values = review(facts)
    setattr(values[0], attribute, value)

    with pytest.raises(ValueError) as error:
        validate_database_facts_review(facts, values)

    assert str(error.value) == f"최종 정리의 DB 구조 확인값이 전달한 사실과 다릅니다. 테이블 순서 1: {field}."
    assert "fake_secret" not in str(error.value) and "fake-secret" not in str(error.value)
    assert "999" not in str(error.value)


def test_feedback_reports_all_mismatches_in_fact_order_even_if_review_order_changes():
    request, impact, source = inputs()
    impact["observedTables"].append("example.unrelated")
    facts = reconciliation_facts(request, impact, source)
    values = review(facts)
    values[0].collected_column_count = 999
    values[0].included_indexes = []
    values[1].included_column_names = ["fake_secret_column"]
    values.reverse()

    with pytest.raises(ValueError) as error:
        validate_database_facts_review(facts, values)

    assert str(error.value) == ("최종 정리의 DB 구조 확인값이 전달한 사실과 다릅니다. "
                                "테이블 순서 1: collectedColumnCount, includedIndexes; "
                                "테이블 순서 2: includedColumnNames.")
    assert "example" not in str(error.value) and "fake_secret" not in str(error.value)


@pytest.mark.parametrize("change", ["missing", "duplicate", "unknown"])
def test_missing_duplicate_and_unknown_tables_use_only_the_fixed_tables_field(change):
    facts = reconciliation_facts(*inputs())
    values = review(facts)
    if change == "missing":
        values = []
    elif change == "duplicate":
        values.append(values[0])
    else:
        values[0].table = "fake_secret_schema.fake_secret_table"

    with pytest.raises(ValueError) as error:
        validate_database_facts_review(facts, values)

    assert str(error.value) == "최종 정리의 DB 구조 확인값이 전달한 사실과 다릅니다. 확인 필드: tables."


def test_index_attribute_mismatch_reaches_model_feedback_without_echoing_either_value():
    from app.services.llm_service import LlmService

    request, impact, source = inputs()
    request["databaseSummary"]["tables"][0]["indexes"][0]["name"] = "fake_secret_original_index"
    facts = reconciliation_facts(request, impact, source)
    values = review(facts)
    values[0].included_indexes[0].name = "fake_secret_model_index"

    with pytest.raises(ValueError) as error:
        validate_database_facts_review(facts, values)

    assert LlmService._safe_validation_code(error.value) == "FINAL_DB_FACTS_MISMATCH"
    feedback = LlmService._safe_validation_feedback(error.value)
    assert "테이블 순서 1: includedIndexes" in feedback
    assert "fake_secret" not in feedback and "example.records" not in feedback
