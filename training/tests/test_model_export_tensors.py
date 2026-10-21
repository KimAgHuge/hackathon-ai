"""소형 합성 하이브리드 모델로 병합 수식과 HF 저장 형식 복원을 검사한다."""

import itertools
import hashlib
import json
import math
import struct
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from model_export_tensors import (ExportError, TensorReader, canonical_name, export_hf, merge_lora,
                                  model_shapes, restore_hf_tensor, source_layout, tensor_header)


class Array:
    """외부 수치 라이브러리와 GPU 없이 작은 텐서의 수학을 확인한다."""
    def __init__(self, values, shape, dtype="F32"):
        self.values, self.shape, self.dtype = list(values), tuple(shape), dtype
        self.size = math.prod(shape)
        self.itemsize = {"F16": 2, "BF16": 2, "F32": 4, "U32": 4}[dtype]
        if self.size != len(self.values):
            raise AssertionError("합성 텐서 길이가 다릅니다.")

    def _elementwise(self, value, operation):
        values = value.values if isinstance(value, Array) else [value] * self.size
        return Array([operation(left, right) for left, right in zip(self.values, values)], self.shape, self.dtype)

    def __add__(self, value):
        return self._elementwise(value, lambda a, b: a + b)

    def __sub__(self, value):
        return self._elementwise(value, lambda a, b: a - b)

    def __rmul__(self, value):
        return self._elementwise(value, lambda a, b: a * b)

    def __matmul__(self, right):
        rows, inner = self.shape
        other, cols = right.shape
        if inner != other:
            raise AssertionError("행렬곱 차원이 다릅니다.")
        values = [sum(self.values[row * inner + index] * right.values[index * cols + col]
                      for index in range(inner)) for row in range(rows) for col in range(cols)]
        return Array(values, (rows, cols), self.dtype)

    def astype(self, dtype):
        return Array(self.values, self.shape, dtype)

    @property
    def T(self):
        return self.moveaxis(0, 1)

    def moveaxis(self, source, destination):
        axes = list(range(len(self.shape)))
        axes.insert(destination, axes.pop(source))
        shape = tuple(self.shape[axis] for axis in axes)
        values = []
        for index in itertools.product(*(range(size) for size in shape)):
            original = [0] * len(shape)
            for position, axis in enumerate(axes):
                original[axis] = index[position]
            offset = 0
            for value, size in zip(original, self.shape):
                offset = offset * size + value
            values.append(self.values[offset])
        return Array(values, shape, self.dtype)


FORMATS = {"F16": "e", "F32": "f", "U32": "I"}


def save_tensors(path, tensors):
    payload, header = b"", {}
    for name, value in tensors.items():
        if value.dtype == "BF16":
            data = b"".join(struct.pack("<H", struct.unpack("<I", struct.pack("<f", item))[0] >> 16) for item in value.values)
        else:
            data = struct.pack("<" + FORMATS[value.dtype] * value.size, *value.values)
        header[name] = {"dtype": value.dtype, "shape": list(value.shape), "data_offsets": [len(payload), len(payload) + len(data)]}
        payload += data
    encoded = json.dumps(header).encode()
    encoded += b" " * (-len(encoded) % 8)
    Path(path).write_bytes(struct.pack("<Q", len(encoded)) + encoded + payload)


class Reader:
    def __init__(self, path, **_):
        self.path = Path(path)

    def __enter__(self):
        self.header = tensor_header(self.path)
        return self

    def __exit__(self, *_):
        return False

    def get_tensor(self, name):
        item = self.header[name]
        start, stop = item["data_offsets"]
        with self.path.open("rb") as stream:
            size = struct.unpack("<Q", stream.read(8))[0]
            stream.seek(8 + size + start)
            data = stream.read(stop - start)
        values = struct.unpack("<" + FORMATS[item["dtype"]] * math.prod(item["shape"]), data)
        return Array(values, item["shape"], item["dtype"])


class Bool:
    def __init__(self, value):
        self.value = value

    def item(self):
        return self.value


class CpuMath:
    cpu, float16, float32 = "cpu", "F16", "F32"
    def __init__(self):
        self.loads = 0

    def set_default_device(self, device):
        if device != self.cpu:
            raise AssertionError("GPU가 요청됐습니다.")

    def eval(self, *_):
        pass

    def clear_cache(self):
        pass

    def isfinite(self, value):
        return all(math.isfinite(item) for item in value.values)

    def all(self, value):
        return Bool(value)

    def save_safetensors(self, path, tensors, **_):
        save_tensors(path, tensors)

    def load(self, source, *, format):
        if format != "safetensors":
            raise AssertionError("가중치 형식이 다릅니다.")
        length = struct.unpack("<Q", source.read(8))[0]
        header = json.loads(source.read(length))
        result = {}
        for name, record in header.items():
            start, stop = record["data_offsets"]
            source.seek(8 + length + start)
            data = source.read(stop - start)
            if record["dtype"] == "BF16":
                values = [struct.unpack("<f", struct.pack("<I", value << 16))[0]
                          for value in struct.unpack("<" + "H" * (len(data) // 2), data)]
            else:
                values = struct.unpack("<" + FORMATS[record["dtype"]] * math.prod(record["shape"]), data)
            result[name] = Array(values, record["shape"], "F32" if record["dtype"] == "BF16" else record["dtype"])
        return result

    def dequantize(self, weight, scales, biases, *, group_size, bits, mode):
        if bits != 4 or mode != "affine":
            raise AssertionError("시험 양자화 형식이 다릅니다.")
        rows, packed = weight.shape
        cols = packed * 8
        groups = cols // group_size
        values = []
        for row in range(rows):
            for col in range(cols):
                code = (weight.values[row * packed + col // 8] >> (col % 8 * 4)) & 15
                index = row * groups + col // group_size
                values.append(code * scales.values[index] + biases.values[index])
        return Array(values, (rows, cols))


def config():
    return {"model_type": "qwen3_5", "tie_word_embeddings": False, "hidden_size": 32,
            "intermediate_size": 64, "num_hidden_layers": 2, "vocab_size": 16,
            "num_attention_heads": 2, "num_key_value_heads": 1, "head_dim": 16,
            "linear_num_key_heads": 1, "linear_num_value_heads": 2,
            "linear_key_head_dim": 4, "linear_value_head_dim": 4,
            "linear_conv_kernel_dim": 4, "full_attention_interval": 2,
            "quantization": {"bits": 4, "group_size": 32}}


class ModelExportTensorTest(unittest.TestCase):
    def test_expected_tensor_schema_matches_all_public_pinned_base_headers(self):
        fixture = json.loads((Path(__file__).parent / "fixtures/qwen3_8_27b_mlx_header.json").read_text())
        _, shapes = model_shapes(fixture["config"])
        group = fixture["config"]["quantization"]["group_size"]
        metadata = {}
        for name, shape in shapes.items():
            if len(shape) == 2:
                rows, cols = shape
                self.assertEqual(cols % group, 0)
                metadata[name] = {"dtype": "U32", "shape": [rows, cols // 8]}
                for suffix in (".scales", ".biases"):
                    metadata[name.removesuffix(".weight") + suffix] = {"dtype": "BF16", "shape": [rows, cols // group]}
            else:
                stored_shape = (shape[0], shape[2], shape[1]) if name.endswith("conv1d.weight") else shape
                metadata[name] = {"dtype": "BF16", "shape": list(stored_shape)}
        actual = hashlib.sha256(json.dumps(metadata, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        self.assertEqual(len(shapes), 851)
        self.assertEqual(len(metadata), fixture["textTensorCount"])
        self.assertEqual(actual, fixture["textHeaderSha256"])
        self.assertEqual(fixture["sourceTensorCount"] - len(metadata), fixture["excludedVisionTensorCount"])
        for name, value in fixture["specialTensors"].items():
            self.assertEqual(metadata[name], value)

    def test_lora_merge_matches_direct_mlx_forward_and_preserves_scale(self):
        weight = Array([0.1, 0.2, 0.3, -0.2, 0.4, 0.1], (2, 3))
        a = Array([1, 2, 3, 4, 5, 6], (3, 2))
        b = Array([0.01, -0.03, 0.02, 0.04], (2, 2))
        x = Array([0.3, -0.1, 0.2], (1, 3))
        expected = x @ weight.T + 8.0 * ((x @ a) @ b)
        actual = x @ merge_lora(weight, a, b, 8.0).T
        for left, right in zip(expected.values, actual.values):
            self.assertAlmostEqual(left, right, places=12)
        with self.assertRaises(ExportError):
            merge_lora(weight, b, a, 8.0)

    def test_norm_and_convolution_restore_exact_hf_semantics(self):
        hf_norm = Array([-0.2, 0.3, 0.6], (3,))
        mlx_norm = hf_norm + 1.0
        restored = restore_hf_tensor("model.layers.0.input_layernorm.weight", mlx_norm, "mlx")
        for left, right in zip(hf_norm.values, restored.values):
            self.assertAlmostEqual(left, right)
        gated = restore_hf_tensor("model.layers.0.linear_attn.norm.weight", mlx_norm, "mlx")
        self.assertEqual(gated.values, mlx_norm.values)
        conv = Array(range(12), (3, 1, 4))
        restored = restore_hf_tensor("model.layers.0.linear_attn.conv1d.weight", conv.moveaxis(2, 1), "mlx")
        self.assertEqual((restored.shape, restored.values), (conv.shape, conv.values))
        self.assertIs(restore_hf_tensor("model.norm.weight", hf_norm, "hf"), hf_norm)

    def test_tensor_header_rejects_trailing_bytes_and_overlapping_offsets(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "weights.safetensors"
            save_tensors(path, {"a": Array([1, 2], (2,))})
            self.assertEqual(tensor_header(path)["a"]["shape"], [2])
            path.write_bytes(path.read_bytes() + b"unexpected")
            with self.assertRaises(ExportError):
                tensor_header(path)

    def test_model_layout_rejects_mixed_hf_and_mlx_convolutions(self):
        shapes = {"model.layers.0.linear_attn.conv1d.weight": (3, 1, 4),
                  "model.layers.1.linear_attn.conv1d.weight": (3, 1, 4)}
        tensors = {key: {"shape": shape} for key, shape in shapes.items()}
        tensors[next(iter(tensors))]["shape"] = (3, 4, 1)
        with self.assertRaises(ExportError):
            source_layout(tensors, shapes)
        with self.assertRaises(ExportError):
            canonical_name("vision_tower.layers.0.weight")

    def test_native_tensor_stream_exposes_only_requested_bfloat16_tensor(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "source.safetensors"
            save_tensors(path, {"unrelated": Array([99] * 8, (8,)),
                                "chosen": Array([1.25, -0.5, 0.0, 2.5], (2, 2), "BF16")})
            record = {"file": path, "name": "chosen", **tensor_header(path)["chosen"]}
            with TensorReader(record) as source:
                raw = source.read()
                self.assertNotIn(b"unrelated", raw)
                self.assertEqual(len(raw), source.length)
                source.seek(0)
                values = CpuMath().load(source, format="safetensors")
            self.assertEqual(set(values), {"chosen"})
            self.assertEqual(values["chosen"].values, [1.25, -0.5, 0.0, 2.5])

    def test_complete_small_hybrid_model_export_dequantizes_and_merges_all_tensors(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model, adapter, output = root / "model", root / "adapter", root / "hf"
            model.mkdir()
            adapter.mkdir()
            base_config = config()
            (model / "config.json").write_text(json.dumps(base_config))
            for name in ("tokenizer.json", "tokenizer_config.json"):
                (model / name).write_text("{}")
            _, shapes = model_shapes(base_config)
            tensors = {}
            for name, shape in shapes.items():
                source = "language_model." + name
                if len(shape) == 2 and shape[1] % 32 == 0:
                    rows, cols = shape
                    prefix = source.removesuffix(".weight")
                    tensors[source] = Array([0x76543210] * (rows * cols // 8), (rows, cols // 8), "U32")
                    tensors[prefix + ".scales"] = Array([0.1] * (rows * cols // 32), (rows, cols // 32))
                    tensors[prefix + ".biases"] = Array([-0.2] * (rows * cols // 32), (rows, cols // 32))
                else:
                    value = Array([0.25] * math.prod(shape), shape, "BF16")
                    if name.endswith("conv1d.weight"):
                        value = value.moveaxis(2, 1)
                    elif name.endswith(("input_layernorm.weight", "post_attention_layernorm.weight", "q_norm.weight", "k_norm.weight")) or name == "model.norm.weight":
                        value = value + 1.0
                    tensors[source] = value
            tensors["vision_tower.patch_embed.proj.weight"] = Array([0.25] * 32, (2, 2, 2, 2, 2), "BF16")
            save_tensors(model / "model.safetensors", tensors)
            params = {"rank": 4, "scale": 8.0, "dropout": 0.0,
                      "keys": ["mlp.gate_proj", "mlp.up_proj", "mlp.down_proj"]}
            (adapter / "adapter_config.json").write_text(json.dumps({"fine_tune_type": "lora", "num_layers": 1, "lora_parameters": params}))
            loras = {}
            for key in params["keys"]:
                name = "model.layers.1." + key
                rows, cols = shapes[name + ".weight"]
                loras["language_model." + name + ".lora_a"] = Array([0.01] * (cols * 4), (cols, 4))
                loras["language_model." + name + ".lora_b"] = Array([0.02] * (rows * 4), (4, rows))
            save_tensors(adapter / "adapters.safetensors", loras)
            events = []
            summary = export_hf(model, adapter, output, lambda *value: events.append(value), mx=CpuMath())
            self.assertEqual(summary, {"tensors": len(shapes), "sourceLayout": "mlx"})
            index = json.loads((output / "model.safetensors.index.json").read_text())["weight_map"]
            self.assertEqual(set(index), set(shapes))
            with Reader(output / index["model.norm.weight"]) as reader:
                self.assertEqual(reader.get_tensor("model.norm.weight").values, [0.25] * 32)
                weight = reader.get_tensor("model.layers.1.mlp.gate_proj.weight")
                self.assertEqual(weight.shape, (64, 32))
                self.assertAlmostEqual(weight.values[0], -0.2 + 8 * 4 * 0.01 * 0.02, places=4)
                untouched = reader.get_tensor("model.layers.0.mlp.gate_proj.weight")
                self.assertAlmostEqual(untouched.values[0], -0.2, places=4)
            hf_config = json.loads((output / "config.json").read_text())
            self.assertNotIn("quantization", hf_config)
            self.assertEqual(hf_config["architectures"], ["Qwen3_5ForCausalLM"])
            self.assertTrue(events and all(event[0] == "FUSING" for event in events))
            with self.assertRaises(ExportError):
                export_hf(model, adapter, output, lambda *_: None, mx=CpuMath())


if __name__ == "__main__":
    unittest.main()
