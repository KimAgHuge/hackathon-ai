import copy
import json
from pathlib import Path
import shutil
import subprocess

import pytest

from app.models.analysis import GeneralAnalysisResponse, RequirementGeneralAnalysisResponse
from app.services.database_synthesis import FinalSynthesisUpdate
from app.services.response_repairs import constrain_evidence, repair_contract


def array_schema(property_schema):
    if property_schema.get("type") == "array":
        return property_schema
    arrays = [branch for branch in property_schema.get("anyOf", []) if branch.get("type") == "array"]
    assert len(arrays) == 1
    return arrays[0]


def file_branches(schema):
    items = array_schema(schema["properties"]["evidences"])["items"]
    branches = items.get("anyOf", [items])
    return {branch["properties"]["file"]["const"]: branch for branch in branches}


@pytest.mark.parametrize("response_type", [GeneralAnalysisResponse, RequirementGeneralAnalysisResponse, FinalSynthesisUpdate])
def test_generation_schema_keeps_file_and_line_pairs_together(response_type):
    schema = response_type.model_json_schema(by_alias=True)
    allowed = [("service/storage.rb", 7), ("web/widget.ts", 24), ("service/storage.rb", 18), ("docs/flow.md", None)]
    before = copy.deepcopy(schema)

    constrained = constrain_evidence(schema, allowed)

    branches = file_branches(constrained)
    assert set(branches) == {"service/storage.rb", "web/widget.ts", "docs/flow.md"}
    expected = {"service/storage.rb": {7, 18, None}, "web/widget.ts": {24, None}, "docs/flow.md": {None}}
    for filename, branch in branches.items():
        assert branch["type"] == "object"
        assert branch["properties"]["file"] == {"type": "string", "const": filename}
        assert "file" in branch["required"]
        assert "line" not in branch["required"]
        assert set(branch["properties"]["line"]["type"]) == {"integer", "null"}
        assert set(branch["properties"]["line"]["enum"]) == expected[filename]
        assert branch["additionalProperties"] is True
    assert schema == before
    assert {key: value for key, value in constrained["properties"].items() if key != "evidences"} == {
        key: value for key, value in schema["properties"].items() if key != "evidences"
    }
    assert {key: value for key, value in constrained.items() if key != "properties"} == {
        key: value for key, value in schema.items() if key != "properties"
    }


@pytest.mark.parametrize("response_type", [GeneralAnalysisResponse, RequirementGeneralAnalysisResponse, FinalSynthesisUpdate])
def test_evidence_repair_uses_the_same_constraints_without_adding_other_fields(response_type):
    allowed = [("lib/read.ex", 3), ("lib/write.ex", 11)]
    code = "FINAL_EVIDENCE_NOT_OBSERVED" if response_type is FinalSynthesisUpdate else "GENERAL_EVIDENCE_NOT_OBSERVED"
    fields, patch = repair_contract(response_type, ValueError("수집 범위를 벗어난 파일 근거입니다."), code)
    full = constrain_evidence(response_type.model_json_schema(by_alias=True), allowed)
    scoped_patch = constrain_evidence(patch, allowed)
    assert fields == ["evidences"]
    assert set(scoped_patch["properties"]) == {"evidences"}
    assert scoped_patch["required"] == ["evidences"]
    assert scoped_patch["additionalProperties"] is False
    assert scoped_patch["properties"]["evidences"] == full["properties"]["evidences"]


def test_nullable_final_evidence_and_default_are_preserved():
    schema = FinalSynthesisUpdate.model_json_schema(by_alias=True)
    original = copy.deepcopy(schema["properties"]["evidences"])
    constrained = constrain_evidence(schema, [("server/queue.go", 5)])
    actual = constrained["properties"]["evidences"]
    assert any(branch.get("type") == "null" for branch in actual["anyOf"])
    assert actual["default"] is None
    assert actual["default"] == original["default"]
    assert "evidences" not in constrained["required"]


@pytest.mark.parametrize("response_type", [GeneralAnalysisResponse, FinalSynthesisUpdate])
def test_empty_reference_scope_allows_only_an_empty_evidence_array(response_type):
    constrained = constrain_evidence(response_type.model_json_schema(by_alias=True), [])
    array = array_schema(constrained["properties"]["evidences"])
    assert array["maxItems"] == 0
    assert "enum" not in array or array["enum"] != []


@pytest.mark.parametrize("schema", [{"type": "object"}, {"type": "object", "properties": {"summary": {"type": "string"}}}])
def test_contracts_without_evidence_are_not_expanded(schema):
    before = copy.deepcopy(schema)
    result = constrain_evidence(schema, [("server/worker.go", 3)])
    assert result == before and result is not schema
    assert schema == before


def test_none_scope_does_not_restrict_existing_evidence_contract():
    schema = GeneralAnalysisResponse.model_json_schema(by_alias=True)
    result = constrain_evidence(schema, None)
    assert result == schema and result is not schema
    result["properties"]["summary"]["description"] = "출력 사본에서만 변경합니다."
    assert "description" not in schema["properties"]["summary"]


def test_other_targeted_repairs_do_not_gain_unrequested_evidence_fields():
    fields, patch = repair_contract(GeneralAnalysisResponse, ValueError("문장 확인이 필요합니다."), "PLAIN_SUMMARY_FORMAT")
    constrained = constrain_evidence(patch, [("lib/store.clj", 9)])
    assert fields == ["plainLanguageSummary"]
    assert constrained == patch
    assert "evidences" not in constrained["properties"]


def test_reference_generator_duplicates_and_repeated_constraints_are_stable():
    references = [("src/shared.cs", 3), ("src/other.cs", None), ("src/shared.cs", 3), ("src/shared.cs", 8)]
    schema = GeneralAnalysisResponse.model_json_schema(by_alias=True)
    once = constrain_evidence(schema, (reference for reference in references))
    repeated = constrain_evidence(once, references)
    assert once == repeated
    branch = file_branches(once)["src/shared.cs"]
    assert len(branch["properties"]["line"]["enum"]) == 3


def test_hundreds_of_references_are_grouped_without_truncating_or_expanding_cross_products():
    references = [(f"modules/segment_{file_number}/storage.py", file_number * 100 + line_number)
                  for file_number in range(60) for line_number in range(1, 11)]
    before = copy.deepcopy(references)
    constrained = constrain_evidence(GeneralAnalysisResponse.model_json_schema(by_alias=True), references)
    branches = file_branches(constrained)
    assert len(branches) == 60
    assert sum(len(branch["properties"]["line"]["enum"]) - 1 for branch in branches.values()) == 600
    assert set(branches["modules/segment_59/storage.py"]["properties"]["line"]["enum"]) == {*range(5901, 5911), None}
    assert len(json.dumps(constrained, ensure_ascii=False, separators=(",", ":"))) < 45000
    assert references == before


def test_scoped_schema_can_be_changed_without_mutating_input_schema_or_references():
    schema = GeneralAnalysisResponse.model_json_schema(by_alias=True)
    references = [("core/router.scala", 12)]
    before_schema, before_references = copy.deepcopy(schema), copy.deepcopy(references)
    constrained = constrain_evidence(schema, references)
    file_branches(constrained)["core/router.scala"]["properties"]["line"]["enum"].append(999)
    constrained["$defs"]["RiskAssessment"]["description"] = "출력 사본에만 추가합니다."
    assert schema == before_schema
    assert references == before_references


def test_optional_local_json_schema_validator_rejects_cross_file_lines_and_accepts_extra_keys():
    node = shutil.which("node")
    frontend = Path(__file__).resolve().parents[2] / "frontend"
    if node is None or not frontend.is_dir():
        pytest.skip("선택 검사에 필요한 Node와 프론트 의존성이 없습니다.")
    schema = constrain_evidence(GeneralAnalysisResponse.model_json_schema(by_alias=True), [
        ("service/storage.rb", 7), ("service/storage.rb", 18), ("web/widget.ts", 24), ("docs/flow.md", None),
    ])
    cases = [
        ({"file": "service/storage.rb", "line": 7, "reason": "실제 전달한 코드 근거입니다.", "kind": "CALL"}, True),
        ({"file": "web/widget.ts", "line": 24}, True),
        ({"file": "service/storage.rb", "line": None}, True),
        ({"file": "web/widget.ts"}, True),
        ({"file": "docs/flow.md", "line": None}, True),
        ({"file": "service/storage.rb", "line": 24}, False),
        ({"file": "web/widget.ts", "line": 7}, False),
        ({"file": "docs/flow.md", "line": 7}, False),
        ({"file": "unknown/made-up.py", "line": None}, False),
        ({"file": "service/storage.rb", "line": "7"}, False),
        ({"file": "service/storage.rb", "line": True}, False),
        ({"file": "service/storage.rb", "line": -1}, False),
        ({"line": 7}, False),
    ]
    # AI 서버만 설치한 환경에서는 계약 검사를 수행하고 이 선택 검사만 건너뛴다.
    script = """
        import fs from 'node:fs';
        let Ajv;
        try { Ajv = (await import('ajv/dist/2020.js')).default; }
        catch {
            try { Ajv = (await import('ajv')).default; }
            catch { process.exit(77); }
        }
        const { schema, cases } = JSON.parse(fs.readFileSync(0, 'utf8'));
        const validate = new Ajv({ strict: false, validateFormats: false }).compile(schema);
        process.stdout.write(JSON.stringify(cases.map(([evidence]) => validate([evidence]))));
    """
    result = subprocess.run([node, "--input-type=module", "-e", script], cwd=frontend,
                            input=json.dumps({"schema": schema["properties"]["evidences"], "cases": cases}),
                            text=True, capture_output=True, timeout=15, check=False)
    if result.returncode == 77:
        pytest.skip("선택 검사에 필요한 프론트 JSON Schema 검사기가 없습니다.")
    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout) == [expected for _, expected in cases]
