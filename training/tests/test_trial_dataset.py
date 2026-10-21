"""단일 프로젝트의 요구사항 누출과 정답 잘림을 막는 시험 준비 규칙을 확인한다."""

import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from dataset_contract import DatasetError
from test_data_tools import case
from trial_dataset import prepare_trial, requirement_groups, tokenize_messages


class Tokenizer:
    """GPU 없이 메시지 경계와 길이 보존만 검증하는 대체 토크나이저다."""

    def apply_chat_template(self, messages, *, return_dict, enable_thinking, add_generation_prompt=False):
        assert return_dict is False and enable_thinking is False
        text = "".join(f"[{m['role']}]" + m["content"] + ";" for m in messages)
        if add_generation_prompt:
            text += "[assistant]"
        return list(text.encode())


def records():
    """같은 프로젝트의 서로 다른 세 원 요구사항을 만든다."""
    values = [case(f"p{i}", related=False) for i in range(3)]
    for i, record in enumerate(values):
        record["projectGroup"] = "one-project"
        record["request"]["sourceSummary"]["changeRequirement"]["requirementId"] = f"requirement-{i}"
    return values


class TrialDatasetTests(unittest.TestCase):
    def setUp(self):
        self.records = records()
        self.plan = {"train": ["case-p0"], "valid": ["case-p1"], "test": ["case-p2"]}

    def test_single_project_is_split_by_original_requirement(self):
        assignment, families = requirement_groups(self.records, self.plan)
        self.assertEqual(assignment, {"case-p0": "train", "case-p1": "valid", "case-p2": "test"})
        self.assertEqual(len(set(families.values())), 3)

    def test_follow_up_and_version_cannot_leak_into_another_split(self):
        follow_up = copy.deepcopy(self.records[0])
        follow_up["caseId"] = "case-follow-up"
        follow_up["snapshot"]["requirementVersionId"] = "later-version"
        follow_up["request"]["sourceSummary"]["changeRequirement"]["refinedContent"] = "같은 요구사항의 추가 답변입니다."
        assignment, _ = requirement_groups([*self.records, follow_up], self.plan)
        self.assertEqual(assignment["case-follow-up"], "train")
        with self.assertRaises(DatasetError):
            requirement_groups([*self.records, follow_up], {**self.plan, "valid": ["case-p1", "case-follow-up"]})

    def test_missing_original_requirement_and_duplicate_input_are_rejected(self):
        missing = copy.deepcopy(self.records)
        del missing[0]["request"]["sourceSummary"]["changeRequirement"]["requirementId"]
        with self.assertRaises(DatasetError):
            requirement_groups(missing, self.plan)
        duplicate = copy.deepcopy(self.records[0])
        duplicate["caseId"] = "duplicate"
        with self.assertRaises(DatasetError):
            requirement_groups([*self.records, duplicate], self.plan)

    def test_token_boundary_preserves_the_whole_answer(self):
        messages = [{"role": "user", "content": "근거 전체"}, {"role": "assistant", "content": "정답 전체"}]
        tokens, offset = tokenize_messages(Tokenizer(), messages)
        self.assertEqual(bytes(tokens[offset:]).decode(), "정답 전체;")

    def test_evidence_removed_from_rendered_input_is_rejected(self):
        record = self.records[0]
        record["request"]["relations"] = [{"file": f"src/reference{i}.py", "line": 7,
            "schema": "public", "table": "projects"} for i in range(81)]
        record["answers"]["general"]["evidences"] = [{"file": "src/reference80.py", "line": 7,
            "description": "원본에는 있지만 실제 메시지에서 생략되는 근거입니다."}]
        with self.assertRaises(DatasetError):
            prepare_trial(self.records, self.plan, Tokenizer(), 100000)

    def test_overlong_sample_is_excluded_without_truncating_original(self):
        original = copy.deepcopy(self.records)
        samples, manifest = prepare_trial(self.records, self.plan, Tokenizer(), 100000)
        self.assertEqual(len(samples["train"]), 1)
        self.assertEqual(manifest["truncatedSamples"], 0)
        with self.assertRaises(DatasetError):
            prepare_trial(self.records, self.plan, Tokenizer(), 10)
        self.assertEqual(self.records, original)


if __name__ == "__main__":
    unittest.main()
