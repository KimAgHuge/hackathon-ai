"""MLX가 설치된 검사 환경에서만 작은 합성 텐서의 실제 CPU 내보내기를 확인한다."""

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from model_export_tensors import TensorReader, tensor_header


@unittest.skipUnless(importlib.util.find_spec("mlx") is not None, "MLX가 설치되지 않아 실제 CPU 수치 검사를 건너뜁니다.")
class ModelExportMlxCpuTest(unittest.TestCase):
    def test_native_bfloat16_tensor_stream_loads_only_the_requested_tensor(self):
        try:
            import mlx.core as mx
        except ImportError:
            self.skipTest("현재 실행 환경에서 MLX 장비 초기화를 사용할 수 없습니다.")
        mx.set_default_device(mx.cpu)
        self.assertEqual(mx.default_device(), mx.cpu)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "native-bfloat16.safetensors"
            mx.save_safetensors(str(path), {"unrelated": mx.array([99]),
                                          "chosen": mx.array([1.25, -0.5, 0.0, 2.5], dtype=mx.bfloat16)})
            entry = {"file": path, "name": "chosen", **tensor_header(path)["chosen"]}
            with TensorReader(entry) as source:
                values = mx.load(source, format="safetensors")
                mx.eval(values)
            self.assertEqual(set(values), {"chosen"})
            self.assertEqual(values["chosen"].dtype, mx.bfloat16)
            self.assertEqual(values["chosen"].tolist(), [1.25, -0.5, 0.0, 2.5])

    def test_complete_small_hybrid_qwen_exports_with_real_cpu_math(self):
        try:
            import mlx.core as mx
        except ImportError:
            self.skipTest("현재 실행 환경에서 MLX 장비 초기화를 사용할 수 없습니다.")
        mx.set_default_device(mx.cpu)
        self.assertEqual(mx.default_device(), mx.cpu)
        # 같은 소형 자료를 쓰되 양자화 복원·행렬곱·저장은 실제 MLX 구현으로 검사한다.
        import test_model_export_tensors as fixture
        result = unittest.TestResult()
        case = fixture.ModelExportTensorTest("test_complete_small_hybrid_model_export_dequantizes_and_merges_all_tensors")
        with patch.object(fixture, "CpuMath", return_value=mx):
            case.run(result)
        if not result.wasSuccessful():
            self.fail("\n".join(error for _, error in result.errors + result.failures))
        self.assertEqual(result.testsRun, 1)
        self.assertFalse(result.skipped)
        self.assertEqual(mx.default_device(), mx.cpu)


if __name__ == "__main__":
    unittest.main()
