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
        "allCollectedColumnsIncluded": table["allCollectedColumnsIncluded"]}) for table in facts["tables"]]


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
