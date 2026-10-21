"""등록된 길이 상한이 자료 분리와 변환에 함께 적용되는지 확인한다."""

import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from dataset_contract import DatasetError
from training_job_prepare import prepare


class TrainingJobPreparationTests(unittest.TestCase):
    def test_profile_length_reaches_both_split_selection_and_dataset_creation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            incoming, model = root / "incoming", root / "model"
            incoming.mkdir()
            model.mkdir()
            (model / "download_manifest.json").write_text(json.dumps({"modelId": "local/model", "revision": "a" * 40}))
            tokenizer = object()
            loader = Mock(return_value=tokenizer)
            samples = {"train": [{"messages": []}], "valid": [{"messages": []}], "test": []}
            plan = {"train": ["first"], "valid": ["second"], "test": ["third"]}
            with patch.dict(sys.modules, {"transformers": SimpleNamespace(AutoTokenizer=SimpleNamespace(from_pretrained=loader))}), \
                    patch("training_job_prepare.automatic_splits", return_value=plan) as split, \
                    patch("training_job_prepare.prepare_trial", return_value=(samples, {"maxTokens": 512})) as build:
                prepare(incoming, root / "dataset", model, max_tokens=512)
            split.assert_called_once_with([], tokenizer, 512)
            build.assert_called_once_with([], plan, tokenizer, 512)
            manifest = json.loads((root / "dataset/manifest.json").read_text())
            self.assertEqual(manifest["maxTokens"], 512)
            self.assertEqual(manifest["model"]["modelId"], "local/model")

    def test_invalid_limit_is_refused_before_model_or_dataset_access(self):
        for value in (True, 0, 32769, float("nan")):
            with self.subTest(value=value), self.assertRaises(DatasetError):
                prepare(Path("missing"), Path("missing"), Path("missing"), value)


if __name__ == "__main__":
    unittest.main()
