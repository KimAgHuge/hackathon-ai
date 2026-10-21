"""완성 모델의 구조 검사와 고정 도구·잠금 전달을 GPU 없이 확인한다."""

import os
import struct
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from model_export_tensors import ExportError, model_shapes
from model_export_worker import (BASE_MODEL_ID, BASE_MODEL_REVISION, LLAMA_CPP_REVISION,
                                 checksum, execute, run, toolchain, verify_gguf, write_json)
from test_model_export_tensors import config


def gguf(path, metadata=None, tensor_type=12):
    """작은 GGUF 구조를 만들어 검사기가 실패해야 할 조건만 바꾼다."""
    text = config()
    _, tensors = model_shapes(text)
    fields = {"general.architecture": "qwen35", "general.file_type": 15,
              "qwen35.block_count": text["num_hidden_layers"], "qwen35.embedding_length": text["hidden_size"],
              "tokenizer.chat_template": "{{ messages }}"}
    fields.update(metadata or {})
    def string(value):
        encoded = value.encode()
        return struct.pack("<Q", len(encoded)) + encoded
    result = b"GGUF" + struct.pack("<IQQ", 3, len(tensors), len(fields))
    for key, value in fields.items():
        result += string(key)
        if isinstance(value, str):
            result += struct.pack("<I", 8) + string(value)
        else:
            result += struct.pack("<II", 4, value)
    stride = 160 if tensor_type == 12 else 512
    for index in range(len(tensors)):
        result += string(f"tensor.{index}") + struct.pack("<IQIQ", 1, 256, tensor_type, index * stride)
    result += b"\0" * (-len(result) % 32)
    result += b"\0" * (len(tensors) * stride)
    path.write_bytes(result)


class ModelExportWorkerTest(unittest.TestCase):
    def test_gguf_requires_complete_model_architecture_quantization_and_chat_template(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "model.gguf"
            gguf(path)
            verify_gguf(path, config())
            for changed in ({"general.architecture": "qwen3"}, {"general.file_type": 1},
                            {"general.type": "adapter"}, {"qwen35.block_count": 3},
                            {"tokenizer.chat_template": ""}):
                gguf(path, changed)
                with self.assertRaises(ExportError):
                    verify_gguf(path, config())
            gguf(path, tensor_type=0)
            with self.assertRaises(ExportError):
                verify_gguf(path, config())
            gguf(path)
            path.write_bytes(path.read_bytes()[:-32])
            with self.assertRaises(ExportError):
                verify_gguf(path, config())

    def test_external_tools_keep_build_and_training_locks_in_the_same_process_group(self):
        with tempfile.TemporaryFile() as first, tempfile.TemporaryFile() as second:
            descriptors = (first.fileno(), second.fileno())
            with patch.dict(os.environ, {"DOENAAI_MODEL_BUILD_LOCK_FDS": ",".join(map(str, descriptors))}):
                with patch("model_export_worker.subprocess.run", return_value=SimpleNamespace(returncode=0, stdout=b"ok\n")) as child:
                    self.assertEqual(run(["fixed-tool"], capture=True), "ok")
                    options = child.call_args.kwargs
                    self.assertEqual(options["pass_fds"], descriptors)
                    self.assertNotIn("start_new_session", options)
                    self.assertNotIn("shell", options)
            with patch.dict(os.environ, {"DOENAAI_MODEL_BUILD_LOCK_FDS": "1,2"}):
                with self.assertRaises(ExportError):
                    run(["fixed-tool"])

    def test_toolchain_rejects_wrong_source_revision_dirty_source_and_wrong_binary_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "build/bin").mkdir(parents=True)
            quantizer = root / "build/bin/llama-quantize"
            quantizer.write_bytes(b"fixed-binary")
            quantizer.chmod(0o700)
            (root / "convert_hf_to_gguf.py").write_text("pass\n")
            python = root / "python"
            python.symlink_to(sys.executable)
            with patch("model_export_worker.run", side_effect=[LLAMA_CPP_REVISION, "", None]):
                self.assertEqual(toolchain(root, python, checksum(quantizer))[0], python)
            with patch("model_export_worker.run", return_value="a" * 40):
                with self.assertRaises(ExportError):
                    toolchain(root, python, checksum(quantizer))
            with patch("model_export_worker.run", side_effect=[LLAMA_CPP_REVISION, " M conversion/qwen.py"]):
                with self.assertRaises(ExportError):
                    toolchain(root, python, checksum(quantizer))
            with patch("model_export_worker.run", side_effect=[LLAMA_CPP_REVISION, ""]):
                with self.assertRaises(ExportError):
                    toolchain(root, python, "0" * 64)

    def test_progress_is_atomic_private_json(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "progress.json"
            write_json(path, {"status": "CONVERTING", "progress": 55})
            self.assertEqual(path.read_text(), '{"status":"CONVERTING","progress":55}\n')
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            self.assertFalse(path.with_suffix(".json.tmp").exists())

    def test_build_reports_complete_only_after_conversion_quantization_and_verification(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model, adapter, output = root / "model", root / "adapter", root / "output"
            for path in (model, adapter, output):
                path.mkdir()
            write_json(model / "config.json", config())
            write_json(model / "download_manifest.json", {"modelId": BASE_MODEL_ID, "revision": BASE_MODEL_REVISION})
            (adapter / "adapters.safetensors").write_bytes(b"original")
            write_json(adapter / "adapter_config.json", {"original": True})
            args = SimpleNamespace(model=str(model), adapter=str(adapter), output=str(output),
                                   progress=str(root / "progress.json"), llama_cpp="/tool",
                                   converter_python="/python", quantizer_sha256="a" * 64)
            commands = []
            def convert(command, **_):
                commands.append(command)
                if command[0] == "/python":
                    self.assertIn("--no-mtp", command)
                    gguf(output / "model-f16.gguf", {"general.file_type": 1}, tensor_type=1)
                else:
                    gguf(output / "model.gguf")
            with patch("model_export_worker.importlib.metadata.version", return_value="0.32.0"), \
                 patch("model_export_worker.shutil.disk_usage", return_value=SimpleNamespace(free=200 * 1024**3)), \
                 patch("model_export_worker.toolchain", return_value=(Path("/python"), Path("/convert"), Path("/quantize"))), \
                 patch("model_export_worker.export_hf", side_effect=lambda _, __, target, ___: target.mkdir()), \
                 patch("model_export_worker.run", side_effect=convert):
                report = execute(args)
            self.assertEqual(len(commands), 2)
            self.assertEqual(report["status"], "complete")
            self.assertEqual(report["artifactSha256"], checksum(output / "model.gguf"))
            self.assertTrue((output / "export_report.json").is_file())
            self.assertFalse((output / "model-f16.gguf").exists())
            self.assertFalse((output / "hf-model").exists())
            self.assertEqual((adapter / "adapters.safetensors").read_bytes(), b"original")


if __name__ == "__main__":
    unittest.main()
