"""GPU 없이 모델별 학습 설정의 자료형과 실행 범위를 확인한다."""

import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from training_profiles import ProfileError, QWEN_PROFILE, lora_parameters, validate_profile


class TrainingProfileTests(unittest.TestCase):
    def test_profile_is_copied_and_lora_arguments_are_explicit(self):
        original = copy.deepcopy(QWEN_PROFILE)
        normalized = validate_profile(original)
        normalized["loraKeys"].append("another.module")
        self.assertEqual(original, QWEN_PROFILE)
        self.assertEqual(lora_parameters(QWEN_PROFILE), {"rank": 4, "scale": 8.0, "dropout": 0.0,
            "keys": ["mlp.gate_proj", "mlp.up_proj", "mlp.down_proj"]})
        self.assertEqual(validate_profile({**QWEN_PROFILE, "loraScale": 8, "loraDropout": 0}), QWEN_PROFILE)

    def test_missing_unknown_and_executable_settings_are_rejected(self):
        for change in ({"command": "python arbitrary.py"}, {"exporter": "../tool"},
                       {"modelType": "other/model"}, {"exportArchitecture": "$(command)"}):
            with self.subTest(change=change), self.assertRaises(ProfileError):
                validate_profile({**QWEN_PROFILE, **change})
        profile = copy.deepcopy(QWEN_PROFILE)
        del profile["modelType"]
        with self.assertRaises(ProfileError):
            validate_profile(profile)

    def test_nonfinite_booleans_and_resource_limits_are_rejected(self):
        for key, value in (("loraRank", True), ("loraLayers", 0), ("loraRank", 129),
                           ("loraScale", float("nan")), ("loraDropout", float("inf")),
                           ("learningRate", -1), ("learningRate", True), ("maxSequenceLength", 32769),
                           ("batchSize", 5), ("gradientCheckpointing", 1), ("loraScale", 0),
                           ("loraKeys", ["mlp.up_proj", "mlp.up_proj"]), ("loraKeys", ["mlp.*"])):
            with self.subTest(key=key, value=value), self.assertRaises(ProfileError):
                validate_profile({**QWEN_PROFILE, key: value})


if __name__ == "__main__":
    unittest.main()
