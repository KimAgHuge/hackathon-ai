"""실제 GPU 없이 공통 학습기의 모듈 대응과 부모 어댑터 보존 규칙을 확인한다."""

import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from dataset_contract import DatasetError
from mlx_lora_train import adapter_settings, read_config, validate_module_targets, validate_parent
from training_profiles import QWEN_PROFILE


class Linear:
    """공식 선형층을 대신하는 자료형 확인용 모듈이다."""


class Layer:
    """실제로 존재하는 하위 모듈 목록만 반환한다."""

    def __init__(self, keys):
        self.keys = keys

    def named_modules(self):
        return self.keys.items()


class CommonMLXWorkerTests(unittest.TestCase):
    def test_registered_targets_must_exist_in_every_selected_layer(self):
        layer = Layer({key: Linear() for key in QWEN_PROFILE["loraKeys"]})
        resolved = validate_module_targets(SimpleNamespace(layers=[layer, layer]), QWEN_PROFILE, (Linear,))
        self.assertEqual(len(resolved), 3)
        self.assertTrue(all(value["layer"] == 1 for value in resolved))
        missing = Layer({"mlp.gate_proj": Linear()})
        for model in (SimpleNamespace(layers=[]), SimpleNamespace(layers=[missing]),
                      SimpleNamespace(layers=[Layer({key: object() for key in QWEN_PROFILE["loraKeys"]})])):
            with self.subTest(model=model), self.assertRaises(DatasetError):
                validate_module_targets(model, QWEN_PROFILE, (Linear,))

    def test_other_architecture_is_validated_by_its_actual_module_paths(self):
        profile = {**QWEN_PROFILE, "modelType": "exaone4", "loraKeys": ["self_attn.q_proj", "self_attn.v_proj"]}
        model = SimpleNamespace(layers=[Layer({key: Linear() for key in profile["loraKeys"]})])
        self.assertEqual([value["module"] for value in validate_module_targets(model, profile, (Linear,))], profile["loraKeys"])
        with self.assertRaises(DatasetError):
            validate_module_targets(model, QWEN_PROFILE, (Linear,))

    def test_parent_configuration_is_exact_and_both_files_are_preserved(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            config = directory / "adapter_config.json"
            weights = directory / "adapters.safetensors"
            config.write_text(json.dumps(adapter_settings(QWEN_PROFILE)))
            weights.write_bytes(b"parent-adapter-fixture")
            hashes = validate_parent(directory, QWEN_PROFILE)
            self.assertEqual(set(hashes), {"adapters.safetensors", "adapter_config.json"})
            self.assertEqual(hashes, validate_parent(directory, QWEN_PROFILE))
            weights.write_bytes(b"changed-parent")
            self.assertNotEqual(hashes, validate_parent(directory, QWEN_PROFILE))
            with self.assertRaises(DatasetError):
                validate_parent(directory, {**QWEN_PROFILE, "loraRank": 8})

    def test_ambiguous_json_and_symlink_configuration_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "profile.json"
            for content in ('{"rank": 4, "rank": 8}', '{"scale": NaN}'):
                path.write_text(content)
                with self.assertRaises(DatasetError):
                    read_config(path)
            link = Path(temporary) / "linked.json"
            link.symlink_to(path)
            with self.assertRaises(DatasetError):
                read_config(link)


if __name__ == "__main__":
    unittest.main()
