"""작은 합성 자료로 모델별 변환과 서명 형식을 GPU 없이 검사한다."""

import copy
import hashlib
import hmac
import json
import math
import struct
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from model_build_jobs import ModelBuildError
from model_delivery import PackageError, canonical
from model_export_profiles import GENERIC_EXPORTER, export_capability
from model_export_tensors import ExportError, dense_model_shapes, verify_fused_dense
from model_export_worker import execute, verify_gguf, write_json
from trained_model_delivery import (DOMAIN, DOMAIN_V2, LLAMA_CPP_REVISION, MLX_LM_VERSION, checksum,
                                    create_package, publish_package, validate_metadata, verify_package)
from training_profiles import QWEN_PROFILE
import test_model_build_jobs as legacy

BASE = {"modelId": "synthetic/EXAONE4-4bit", "revision": "6" * 40}
SOURCE = "exaone4:test"
SECRET = "synthetic-generic-model-delivery-at-least-32"
SUMMARY = {"qualityEvaluation": "NOT_EVALUATED", "productionApproved": False}


def profile(model_type="exaone4", architecture=None):
    return {**copy.deepcopy(QWEN_PROFILE), "modelType": model_type, "exporter": GENERIC_EXPORTER,
            "exportArchitecture": architecture or {"mistral": "llama"}.get(model_type, model_type),
            "loraKeys": ["self_attn.q_proj"]}


def config(model_type="exaone4"):
    return {"model_type": model_type, "architectures": [{"llama": "LlamaForCausalLM", "mistral": "MistralForCausalLM",
             "qwen2": "Qwen2ForCausalLM", "qwen3": "Qwen3ForCausalLM", "exaone4": "Exaone4ForCausalLM"}[model_type]],
            "hidden_size": 256, "intermediate_size": 512, "vocab_size": 512, "num_hidden_layers": 2,
            "num_attention_heads": 4, "num_key_value_heads": 2, "head_dim": 64, "tie_word_embeddings": False}


def safetensors(path, shapes):
    header, offset = {}, 0
    for key, shape in shapes.items():
        size = math.prod(shape) * 4
        header[key] = {"dtype": "F32", "shape": list(shape), "data_offsets": [offset, offset + size]}
        offset += size
    raw = json.dumps(header).encode()
    path.write_bytes(struct.pack("<Q", len(raw)) + raw + b"\0" * offset)


def gguf(path, chosen=None, *, quantized=True, changed=None, change_shape=False):
    chosen = chosen or profile()
    cfg = config(chosen["modelType"])
    _, tensors, _ = dense_model_shapes(cfg, chosen)
    architecture = chosen["exportArchitecture"]
    fields = {"general.architecture": architecture, "general.file_type": 15 if quantized else 1,
              architecture + ".block_count": cfg["num_hidden_layers"], architecture + ".embedding_length": cfg["hidden_size"],
              "tokenizer.chat_template": "{{ messages }}"}
    fields.update(changed or {})
    def string(value):
        encoded = value.encode()
        return struct.pack("<Q", len(encoded)) + encoded
    raw = b"GGUF" + struct.pack("<IQQ", 3, len(tensors), len(fields))
    for key, value in fields.items():
        raw += string(key) + (struct.pack("<I", 8) + string(value) if isinstance(value, str) else struct.pack("<II", 4, value))
    offset = 0
    for index, (name, shape) in enumerate(tensors.items()):
        kind = 12 if quantized and len(shape) == 2 and shape[0] % 256 == 0 else 1
        size = math.prod(shape) // 256 * 144 if kind == 12 else math.prod(shape) * 2
        actual_shape = (shape[0] + 1, *shape[1:]) if change_shape and index == 0 else shape
        raw += string(name) + struct.pack("<I", len(actual_shape)) + struct.pack("<" + "Q" * len(actual_shape), *actual_shape)
        raw += struct.pack("<IQ", kind, offset)
        offset = (offset + size + 31) // 32 * 32
    raw += b"\0" * (-len(raw) % 32)
    path.write_bytes(raw + b"\0" * offset)


class GenericModelExportTests(unittest.TestCase):
    def test_export_requires_explicit_supported_model_exporter_and_matching_architecture(self):
        for model_type in ("llama", "mistral", "qwen2", "qwen3", "exaone4"):
            self.assertTrue(export_capability(profile(model_type), base_model=BASE["modelId"], revision=BASE["revision"])["supported"])
        for changed in ({"modelType": "gemma3"}, {"exporter": "arbitrary-command"},
                        {"exportArchitecture": "qwen35"}, {"loraKeys": ["self_attn.q_norm"]}):
            self.assertFalse(export_capability({**profile(), **changed})["supported"])
        without = profile()
        without.pop("exporter")
        self.assertFalse(export_capability(without)["supported"])
        self.assertFalse(export_capability(QWEN_PROFILE, base_model=BASE["modelId"])["supported"])
        self.assertFalse(export_capability(QWEN_PROFILE, revision="7" * 40)["supported"])

    def test_dense_layout_keeps_model_specific_norms_and_rejects_other_architectures(self):
        for model_type in ("llama", "mistral", "qwen2", "qwen3", "exaone4"):
            shapes, converted, _ = dense_model_shapes(config(model_type), profile(model_type))
            self.assertEqual(len(shapes), len(converted))
            self.assertEqual(shapes["model.layers.1.self_attn.k_proj.weight"], (128, 256))
        shapes, _, _ = dense_model_shapes(config(), profile())
        self.assertIn("model.layers.1.post_feedforward_layernorm.weight", shapes)
        self.assertNotIn("model.layers.1.input_layernorm.weight", shapes)
        for changed in ({"architectures": ["ExaoneMoEForCausalLM"]}, {"num_experts": 4},
                        {"vision_config": {}}, {"rope_traditional": True}, {"model_type": "qwen3"}):
            with self.assertRaises(ExportError):
                dense_model_shapes({**config(), **changed}, profile())

    def test_generic_gguf_checks_actual_tensor_names_dimensions_model_architecture_and_quantization(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "model.gguf"
            for model_type in ("llama", "mistral", "qwen2", "qwen3", "exaone4"):
                chosen = profile(model_type)
                gguf(path, chosen)
                verify_gguf(path, config(model_type), profile=chosen)
            for changed in ({"general.architecture": "qwen35"}, {"general.type": "adapter"},
                            {"general.file_type": 1}, {"exaone4.block_count": 3}, {"tokenizer.chat_template": ""}):
                gguf(path, changed=changed)
                with self.assertRaises(ExportError):
                    verify_gguf(path, config(), profile=profile())
            gguf(path, change_shape=True)
            with self.assertRaises(ExportError):
                verify_gguf(path, config(), profile=profile())

    def test_official_fused_output_must_be_complete_float_hf_layout(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shapes, _, _ = dense_model_shapes(config(), profile())
            write_json(root / "config.json", config())
            safetensors(root / "model.safetensors", shapes)
            self.assertEqual(verify_fused_dense(root, config(), profile()), len(shapes))
            shapes.pop("model.layers.1.self_attn.q_norm.weight")
            safetensors(root / "model.safetensors", shapes)
            with self.assertRaises(ExportError):
                verify_fused_dense(root, config(), profile())

    def test_generic_worker_fuses_current_adapter_once_and_pins_source_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model, adapter, output = [root / name for name in ("model", "adapter", "output")]
            for path in (model, adapter, output):
                path.mkdir()
            write_json(model / "config.json", config())
            write_json(model / "download_manifest.json", BASE)
            chosen = profile()
            write_json(adapter / "adapter_config.json", {"fine_tune_type": "lora", "num_layers": 1,
                "lora_parameters": {"rank": 4, "scale": 8.0, "dropout": 0.0, "keys": chosen["loraKeys"]}})
            safetensors(adapter / "adapters.safetensors", {"model.layers.1.self_attn.q_proj.lora_a": (256, 4),
                                                          "model.layers.1.self_attn.q_proj.lora_b": (4, 256)})
            binding = root / "binding.json"
            write_json(binding, {"baseModel": BASE["modelId"], "baseRevision": BASE["revision"], "profile": chosen})
            args = SimpleNamespace(model=str(model), adapter=str(adapter), output=str(output), binding_file=str(binding),
                progress=str(root / "progress.json"), llama_cpp="/tool", converter_python="/python", quantizer_sha256="a" * 64)
            calls = []
            def run(command, **_):
                calls.append(command)
                if command[0] == sys.executable:
                    self.assertIn("--dequantize", command)
                    self.assertNotIn("--export-gguf", command)
                    target = output / "hf-model"
                    target.mkdir()
                    write_json(target / "config.json", config())
                    shapes, _, _ = dense_model_shapes(config(), chosen)
                    safetensors(target / "model.safetensors", shapes)
                else:
                    gguf(output / ("model-f16.gguf" if command[0] == "/python" else "model.gguf"), quantized=command[0] != "/python")
            original = (adapter / "adapters.safetensors").read_bytes()
            with patch("model_export_worker.importlib.metadata.version", return_value=MLX_LM_VERSION), \
                 patch("model_export_worker.shutil.disk_usage", return_value=SimpleNamespace(free=200 * 1024**3)), \
                 patch("model_export_worker.toolchain", return_value=(Path("/python"), Path("/converter"), Path("/quantizer"))), \
                 patch("model_export_worker.run", side_effect=run):
                result = execute(args)
            self.assertEqual(len(calls), 3)
            self.assertEqual(result["profile"], chosen)
            self.assertEqual(result["architecture"], "exaone4")
            self.assertEqual((adapter / "adapters.safetensors").read_bytes(), original)

    def test_format_two_preserves_signed_source_profile_and_distinct_signing_domain(self):
        with tempfile.TemporaryDirectory() as directory:
            root, chosen = Path(directory), profile()
            artifact, package, shared = root / "model.gguf", root / "package", root / "shared"
            shared.mkdir()
            gguf(artifact)
            result = create_package(artifact, SUMMARY, package, legacy.IDENTIFIER, BASE, SECRET,
                                    source_model=SOURCE, profile=chosen)
            self.assertEqual(result["formatVersion"], 2)
            self.assertEqual(result["sourceModel"], SOURCE)
            self.assertEqual(result["profile"], chosen)
            self.assertEqual(verify_package(package, SECRET), result)
            self.assertEqual(publish_package(package, shared, SECRET), result)
            manifest = json.loads((package / "manifest.json").read_text())
            ready = json.loads((package / "READY.json").read_text())
            signature = hmac.new(SECRET.encode(), DOMAIN_V2 + canonical(manifest), hashlib.sha256).hexdigest()
            self.assertEqual(ready["signature"], signature)
            ready["signature"] = hmac.new(SECRET.encode(), DOMAIN + canonical(manifest), hashlib.sha256).hexdigest()
            write_json(package / "READY.json", ready)
            with self.assertRaises(PackageError):
                verify_package(package, SECRET)
            for changed in ({"architecture": "qwen35"}, {"modelType": "qwen3"}, {"formatVersion": True},
                            {"sourceModel": "bad model"}, {"profile": {**chosen, "command": "arbitrary"}}):
                with self.assertRaises(PackageError):
                    validate_metadata({**result, **changed})

    def test_format_two_rejects_actual_gguf_architecture_different_from_signed_profile(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "model.gguf"
            gguf(artifact, changed={"general.architecture": "llama"})
            with self.assertRaises(PackageError):
                create_package(artifact, SUMMARY, root / "package", legacy.IDENTIFIER, BASE, SECRET,
                               source_model=SOURCE, profile=profile())
            self.assertFalse((root / "package").exists())


class GenericModelBuildTests(unittest.TestCase):
    setUp = legacy.ModelBuildTests.setUp
    ready = legacy.ModelBuildTests.ready
    finish = legacy.ModelBuildTests.finish

    def execute(self, arguments, **options):
        self.calls.append((arguments, options))
        output = Path(arguments[arguments.index("--output") + 1])
        chosen = self.source["profile"]
        gguf(output / "model.gguf", chosen)
        value, size = checksum(output / "model.gguf")
        write_json(output / "export_report.json", {"status": "complete", "artifactSha256": value, "artifactSize": size,
            "quantization": "Q4_K_M", "architecture": chosen["exportArchitecture"], "modelType": chosen["modelType"],
            "exporter": GENERIC_EXPORTER, "profile": chosen,
            "toolchain": {"mlxLmVersion": MLX_LM_VERSION, "llamaCppRevision": LLAMA_CPP_REVISION}})
        binding = Path(arguments[arguments.index("--binding-file") + 1])
        self.assertEqual(json.loads(binding.read_text())["profile"], chosen)
        return legacy.FinishedProcess()

    def prepare(self):
        self.source["target"] = {**legacy.TARGET, "sourceModel": SOURCE, "baseModel": BASE["modelId"], "revision": BASE["revision"]}
        self.source["profile"] = profile()
        self.ready()

    def test_generic_build_preserves_format_two_profile_and_source_identity(self):
        self.prepare()
        self.store.submit(legacy.IDENTIFIER, {})
        result = self.finish()
        self.assertEqual(result["status"], "SUCCEEDED")
        self.assertEqual(result["result"]["sourceModel"], SOURCE)
        self.assertEqual(result["result"]["profile"], profile())
        self.assertEqual(result["result"]["formatVersion"], 2)
        self.assertFalse(list(self.store.work_root.glob("*/attempt-*")))
        package = self.jobs.receiver.shared_dir / "model-deliveries" / result["result"]["version"]
        summary = json.loads((package / "evaluation-summary.json").read_text())
        self.assertEqual(summary["selectedModel"], SOURCE)
        self.assertNotIn("parentJobId", summary)

    def test_continuation_keeps_signed_parent_lineage_without_changing_metadata_contract(self):
        self.prepare()
        parent = "12345678-1234-4567-8123-123456789abc"
        self.source["target"].update(selectedModel="doenaai-trained:web-" + parent, resumeJobId=parent,
                                     parentAdapterSha256="7" * 64, parentConfigSha256="8" * 64)
        self.source["parentAdapter"] = self.root / "private-parent-adapter"
        self.store.submit(legacy.IDENTIFIER, {})
        result = self.finish()
        self.assertEqual(result["status"], "SUCCEEDED")
        self.assertEqual(len(result["result"]), 14)
        package = self.jobs.receiver.shared_dir / "model-deliveries" / result["result"]["version"]
        summary = json.loads((package / "evaluation-summary.json").read_text())
        self.assertEqual(summary["selectedModel"], "doenaai-trained:web-" + parent)
        self.assertEqual(summary["parentJobId"], parent)
        self.assertEqual(summary["parentAdapterSha256"], "7" * 64)
        self.assertEqual(summary["parentConfigSha256"], "8" * 64)
        self.assertNotIn(str(self.root), json.dumps(summary))
        self.assertEqual(self.store._existing_package(package, self.store._source(legacy.IDENTIFIER), legacy.IDENTIFIER), result["result"])
        original = copy.deepcopy(self.source["target"])
        for key, value in (("parentAdapterSha256", "9" * 64), ("parentConfigSha256", "a" * 64),
                           ("resumeJobId", "23456789-1234-4567-8123-123456789abc"), ("selectedModel", "exaone4:other")):
            self.source["target"] = {**original, key: value}
            with self.assertRaises(ModelBuildError):
                self.store._existing_package(package, self.store._source(legacy.IDENTIFIER), legacy.IDENTIFIER)
        self.assertEqual(verify_package(package, legacy.SECRET), result["result"])

    def test_existing_package_without_parent_summary_cannot_be_reused_for_continuation(self):
        self.prepare()
        self.store.submit(legacy.IDENTIFIER, {})
        result = self.finish()
        package = self.jobs.receiver.shared_dir / "model-deliveries" / result["result"]["version"]
        parent = "12345678-1234-4567-8123-123456789abc"
        self.source["target"].update(selectedModel="doenaai-trained:web-" + parent, resumeJobId=parent,
                                     parentAdapterSha256="7" * 64, parentConfigSha256="8" * 64)
        with self.assertRaises(ModelBuildError):
            self.store._existing_package(package, self.store._source(legacy.IDENTIFIER), legacy.IDENTIFIER)

    def test_trainable_profile_without_exporter_is_unavailable_without_starting_a_worker(self):
        self.prepare()
        self.source["profile"].pop("exporter")
        state = self.store.get(legacy.IDENTIFIER)
        self.assertFalse(state["available"])
        self.assertEqual(state["reasonCode"], "MODEL_BUILD_UNSUPPORTED_PROFILE")
        with self.assertRaises(ModelBuildError):
            self.store.submit(legacy.IDENTIFIER, {})
        self.assertFalse(self.calls)

    def test_changed_profile_during_export_is_rejected_without_publishing(self):
        self.prepare()
        normal = self.execute
        def changed(*args, **kwargs):
            result = normal(*args, **kwargs)
            self.source["profile"]["loraScale"] = 16.0
            return result
        self.store.popen_factory = changed
        self.store.submit(legacy.IDENTIFIER, {})
        self.assertEqual(self.finish()["errorCode"], "MODEL_BUILD_SOURCE_INVALID")
        self.assertFalse((self.jobs.receiver.shared_dir / "model-deliveries").exists())
