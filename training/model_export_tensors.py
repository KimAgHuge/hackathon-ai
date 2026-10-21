"""MLX 가중치를 텐서별로 병합하고 Qwen 텍스트 모델의 HF 저장 형식으로 복원한다."""

import gc
import io
import json
import math
import re
import shutil
import struct
from pathlib import Path


class ExportError(ValueError):
    """학습 자료나 로컬 경로를 노출하지 않는 변환 오류다."""


NORM_SUFFIXES = (".input_layernorm.weight", ".post_attention_layernorm.weight",
                 "model.norm.weight", ".q_norm.weight", ".k_norm.weight")
DTYPE_SIZE = {"F16": 2, "BF16": 2, "F32": 4, "U32": 4, "I32": 4, "U8": 1}
TOKENIZER_FILES = ("tokenizer.json", "tokenizer_config.json", "special_tokens_map.json",
                   "tokenizer.model", "vocab.json", "merges.txt", "added_tokens.json",
                   "chat_template.jinja", "generation_config.json")


def read_json(path, limit=16 * 1024**2):
    """작은 JSON의 중복 필드와 유효하지 않은 숫자를 거부한다."""
    path = Path(path)
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= limit:
        raise ExportError("변환 설정 파일의 형식과 크기를 확인하세요.")
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ExportError("변환 설정에 중복 필드가 있습니다.")
            result[key] = value
        return result
    def invalid(_):
        raise ExportError("변환 설정에 유효하지 않은 숫자가 있습니다.")
    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=pairs, parse_constant=invalid)
    except (UnicodeError, json.JSONDecodeError):
        raise ExportError("변환 설정 JSON을 확인하세요.") from None
    if not isinstance(value, dict):
        raise ExportError("변환 설정은 JSON 객체여야 합니다.")
    return value


def tensor_header(path):
    """전체 가중치를 읽지 않고 safetensors 명세와 연속된 데이터 범위를 확인한다."""
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise ExportError("가중치는 일반 파일이어야 합니다.")
    with path.open("rb") as source:
        length = source.read(8)
        if len(length) != 8:
            raise ExportError("가중치 헤더가 불완전합니다.")
        size = struct.unpack("<Q", length)[0]
        if not 2 <= size <= 16 * 1024**2:
            raise ExportError("가중치 헤더 크기가 올바르지 않습니다.")
        try:
            header = json.loads(source.read(size), object_pairs_hook=_unique_pairs)
        except (UnicodeError, json.JSONDecodeError):
            raise ExportError("가중치 헤더 형식을 확인하세요.") from None
    if not isinstance(header, dict):
        raise ExportError("가중치 헤더는 객체여야 합니다.")
    result, ranges = {}, []
    for name, item in header.items():
        if name == "__metadata__":
            continue
        if (not isinstance(name, str) or not name or not isinstance(item, dict)
                or set(item) != {"dtype", "shape", "data_offsets"}
                or item.get("dtype") not in DTYPE_SIZE):
            raise ExportError("지원하지 않는 가중치 텐서입니다.")
        shape, offsets = item["shape"], item["data_offsets"]
        if (not isinstance(shape, list) or not 1 <= len(shape) <= 5
                or any(type(v) is not int or not 1 <= v <= 2**31 for v in shape)
                or not isinstance(offsets, list) or len(offsets) != 2
                or any(type(v) is not int or v < 0 for v in offsets)
                or offsets[1] - offsets[0] != math.prod(shape) * DTYPE_SIZE[item["dtype"]]
                or offsets[1] - offsets[0] > 3 * 1024**3):
            raise ExportError("가중치 텐서의 크기와 범위를 확인하세요.")
        result[name] = item
        ranges.append(offsets)
    end = 0
    for start, stop in sorted(ranges):
        if start != end:
            raise ExportError("가중치 데이터가 겹치거나 누락됐습니다.")
        end = stop
    if not result or path.stat().st_size != 8 + size + end:
        raise ExportError("가중치 데이터 길이가 명세와 다릅니다.")
    return result


def _unique_pairs(items):
    result = {}
    for key, value in items:
        if key in result:
            raise ExportError("가중치 헤더에 중복 필드가 있습니다.")
        result[key] = value
    return result


def canonical_name(name):
    """MLX의 텍스트 모델 접두사만 제거하며 다른 모델 구조는 추측하지 않는다."""
    if name.startswith("language_model."):
        name = name[len("language_model."):]
    elif name.startswith("model.language_model."):
        name = "model." + name[len("model.language_model."):]
    if not re.fullmatch(r"(?:model\.(?:embed_tokens|norm)|lm_head|model\.layers\.[0-9]+\.[A-Za-z0-9_.]+)\.(?:weight|scales|biases)|model\.layers\.[0-9]+\.linear_attn\.(?:A_log|dt_bias)", name):
        raise ExportError("확인하지 않은 모델 텐서 이름입니다.")
    return name


def model_tensors(model):
    """샤드 명세와 실제 헤더를 대조해 각 텐서의 파일을 찾는다."""
    model = Path(model)
    if model.is_symlink() or not model.is_dir():
        raise ExportError("기반 모델 폴더를 확인하세요.")
    files = sorted(model.glob("*.safetensors"))
    if not files or len(files) > 128:
        raise ExportError("기반 모델 가중치 샤드를 확인하세요.")
    raw, normalized = {}, {}
    for file in files:
        for name, item in tensor_header(file).items():
            if name in raw:
                raise ExportError("기반 모델에 중복 텐서가 있습니다.")
            raw[name] = file.name
            # 공식 MLX Qwen 로더와 같이 텍스트 학습에 쓰지 않은 영상 가중치는 제외한다.
            if name.startswith("vision_tower."):
                continue
            canonical = canonical_name(name)
            if canonical in normalized:
                raise ExportError("기반 모델에 중복 텐서가 있습니다.")
            normalized[canonical] = {"file": file, "name": name, **item}
    index_path = model / "model.safetensors.index.json"
    if len(files) > 1 or index_path.exists():
        index = read_json(index_path)
        if index.get("weight_map") != raw:
            raise ExportError("기반 모델 샤드 명세와 실제 텐서가 다릅니다.")
    return normalized


class TensorReader(io.RawIOBase):
    """원본 파일의 한 텐서 범위를 독립 safetensors 파일처럼 보여 주며 큰 복사본을 만들지 않는다."""
    def __init__(self, entry):
        super().__init__()
        self.source = Path(entry["file"]).open("rb")
        try:
            length = struct.unpack("<Q", self.source.read(8))[0]
            start, stop = entry["data_offsets"]
            self.data_offset = 8 + length + start
            self.data_size = stop - start
            record = {entry["name"]: {"dtype": entry["dtype"], "shape": entry["shape"],
                                      "data_offsets": [0, self.data_size]}}
            header = json.dumps(record, separators=(",", ":")).encode("utf-8")
            header += b" " * (-len(header) % 8)
            self.header = struct.pack("<Q", len(header)) + header
            self.length = len(self.header) + self.data_size
            self.position = 0
        except Exception:
            self.source.close()
            raise

    def readable(self):
        return True

    def seekable(self):
        return True

    def tell(self):
        return self.position

    def seek(self, offset, whence=io.SEEK_SET):
        position = offset + (self.position if whence == io.SEEK_CUR else self.length if whence == io.SEEK_END else 0)
        if whence not in (io.SEEK_SET, io.SEEK_CUR, io.SEEK_END) or not 0 <= position <= self.length:
            raise ExportError("가중치 스트림 범위를 벗어났습니다.")
        self.position = position
        return position

    def readinto(self, target):
        view = memoryview(target).cast("B")
        size = min(len(view), self.length - self.position)
        copied = 0
        if self.position < len(self.header):
            copied = min(size, len(self.header) - self.position)
            view[:copied] = self.header[self.position:self.position + copied]
            self.position += copied
        if copied < size:
            self.source.seek(self.data_offset + self.position - len(self.header))
            actual = self.source.readinto(view[copied:size])
            if actual != size - copied:
                raise ExportError("원본 가중치 텐서 데이터가 불완전합니다.")
            self.position += actual
        return size

    def close(self):
        if not self.closed:
            self.source.close()
        super().close()


def model_shapes(config):
    """현재 시험 학습기가 사용하는 조밀한 Qwen 구조의 모든 필수 텐서를 계산한다."""
    if config.get("model_type") != "qwen3_5":
        raise ExportError("이 변환기는 확인한 Qwen 텍스트 모델만 지원합니다.")
    text = config.get("text_config", config)
    if not isinstance(text, dict) or text.get("tie_word_embeddings") is not False:
        raise ExportError("기반 모델의 텍스트 설정을 확인하세요.")
    names = ("hidden_size", "intermediate_size", "num_hidden_layers", "vocab_size",
             "num_attention_heads", "num_key_value_heads", "linear_num_key_heads",
             "linear_num_value_heads", "linear_key_head_dim", "linear_value_head_dim",
             "linear_conv_kernel_dim", "full_attention_interval")
    values = {key: text.get(key) for key in names}
    if any(type(value) is not int or not 1 <= value <= 2**20 for value in values.values()):
        raise ExportError("기반 모델의 차원 설정이 불완전합니다.")
    if text.get("attention_bias", False) is not False or text.get("num_experts", 0) != 0:
        raise ExportError("어텐션 편향이나 전문가 구조는 이 변환기에서 지원하지 않습니다.")
    h, inter, layers, vocab = (values[k] for k in ("hidden_size", "intermediate_size", "num_hidden_layers", "vocab_size"))
    heads, kv = values["num_attention_heads"], values["num_key_value_heads"]
    dim = text.get("head_dim", h // heads)
    if type(dim) is not int or dim < 1 or not 1 <= layers <= 128:
        raise ExportError("기반 모델의 층과 헤드 차원을 확인하세요.")
    key_dim = values["linear_num_key_heads"] * values["linear_key_head_dim"]
    value_dim = values["linear_num_value_heads"] * values["linear_value_head_dim"]
    shapes = {"model.embed_tokens.weight": (vocab, h), "lm_head.weight": (vocab, h), "model.norm.weight": (h,)}
    for layer in range(layers):
        prefix = f"model.layers.{layer}."
        shapes.update({prefix + "input_layernorm.weight": (h,), prefix + "post_attention_layernorm.weight": (h,),
                       prefix + "mlp.gate_proj.weight": (inter, h), prefix + "mlp.up_proj.weight": (inter, h),
                       prefix + "mlp.down_proj.weight": (h, inter)})
        if (layer + 1) % values["full_attention_interval"] == 0:
            shapes.update({prefix + "self_attn.q_proj.weight": (heads * dim * 2, h),
                           prefix + "self_attn.k_proj.weight": (kv * dim, h), prefix + "self_attn.v_proj.weight": (kv * dim, h),
                           prefix + "self_attn.o_proj.weight": (h, heads * dim),
                           prefix + "self_attn.q_norm.weight": (dim,), prefix + "self_attn.k_norm.weight": (dim,)})
        else:
            prefix += "linear_attn."
            shapes.update({prefix + "conv1d.weight": (2 * key_dim + value_dim, 1, values["linear_conv_kernel_dim"]),
                           prefix + "in_proj_qkv.weight": (2 * key_dim + value_dim, h),
                           prefix + "in_proj_z.weight": (value_dim, h),
                           prefix + "in_proj_a.weight": (values["linear_num_value_heads"], h),
                           prefix + "in_proj_b.weight": (values["linear_num_value_heads"], h),
                           prefix + "out_proj.weight": (h, value_dim),
                           prefix + "A_log": (values["linear_num_value_heads"],),
                           prefix + "dt_bias": (values["linear_num_value_heads"],),
                           prefix + "norm.weight": (values["linear_value_head_dim"],)})
    return text, shapes


def source_layout(tensors, shapes):
    """Conv1d의 저장 축으로 MLX 정규화 보정 여부를 확인하고 혼합된 저장 형식을 거부한다."""
    layouts = set()
    for name, shape in shapes.items():
        if not name.endswith("conv1d.weight"):
            continue
        if name not in tensors:
            raise ExportError("기반 모델의 필수 텐서가 누락됐습니다.")
        actual = tuple(tensors[name]["shape"])
        if actual == shape:
            layouts.add("hf")
        elif actual == (shape[0], shape[2], shape[1]):
            layouts.add("mlx")
        else:
            raise ExportError("Conv1d 가중치의 축이 확인한 구조와 다릅니다.")
    if len(layouts) != 1:
        raise ExportError("기반 모델의 저장 형식이 혼합됐거나 확인되지 않았습니다.")
    return layouts.pop()


def restore_hf_tensor(name, value, layout):
    """공식 MLX Qwen sanitize의 정규화 보정과 Conv1d 축 이동을 역으로 적용한다."""
    if layout == "mlx":
        if name.endswith("conv1d.weight"):
            value = value.moveaxis(2, 1)
        elif any(name.endswith(suffix) for suffix in NORM_SUFFIXES):
            value = value - 1.0
    return value


def merge_lora(weight, a, b, scale):
    """MLX LoRA의 저장 방향과 배율 그대로 기반 선형 가중치에 합친다."""
    if (len(weight.shape) != 2 or len(a.shape) != 2 or len(b.shape) != 2
            or a.shape[0] != weight.shape[1] or b.shape[1] != weight.shape[0]
            or a.shape[1] != b.shape[0]):
        raise ExportError("어댑터와 기반 가중치의 차원이 다릅니다.")
    return weight + ((scale * b.T) @ a.T).astype(weight.dtype)


def _quantization(config, entry):
    quant = config.get("quantization", config.get("quantization_config"))
    if not isinstance(quant, dict):
        raise ExportError("MLX 양자화 설정을 확인하세요.")
    override = quant.get(entry["name"].rsplit(".", 1)[0], quant.get(canonical_name(entry["name"]).rsplit(".", 1)[0]))
    params = override if isinstance(override, dict) else quant
    bits, group, mode = params.get("bits"), params.get("group_size"), params.get("mode", "affine")
    if bits != 4 or group not in (32, 64, 128) or mode != "affine":
        raise ExportError("확인한 MLX 4비트 affine 양자화만 변환할 수 있습니다.")
    return group, bits, mode


def export_hf(model, adapter, output, progress, *, mx=None):
    """모델 전체를 로딩하지 않고 작은 출력 샤드와 현재 텐서만 메모리에 유지한다."""
    if mx is None:
        import mlx.core as mx
    model, adapter, output = Path(model), Path(adapter), Path(output)
    config = read_json(model / "config.json")
    text, shapes = model_shapes(config)
    tensors = model_tensors(model)
    layout = source_layout(tensors, shapes)
    main = {name for name in tensors if not name.endswith((".scales", ".biases"))}
    if main != set(shapes):
        raise ExportError("기반 모델의 전체 텐서 구성이 확인한 Qwen 구조와 다릅니다.")
    companions = {name.rsplit(".", 1)[0] + suffix for name in main if tensors[name]["dtype"] == "U32"
                  for suffix in (".scales", ".biases")}
    if set(tensors) - main != companions:
        raise ExportError("기반 모델의 양자화 보조 텐서 구성이 올바르지 않습니다.")
    lora_config = read_json(adapter / "adapter_config.json")
    params = lora_config.get("lora_parameters")
    if (lora_config.get("fine_tune_type") != "lora" or lora_config.get("num_layers") != 1
            or not isinstance(params, dict) or params.get("rank") != 4 or params.get("scale") != 8.0
            or params.get("dropout") != 0.0
            or params.get("keys") != ["mlp.gate_proj", "mlp.up_proj", "mlp.down_proj"]):
        raise ExportError("현재 확인한 마지막 층 MLP 시험 어댑터만 변환할 수 있습니다.")
    adapter_file = adapter / "adapters.safetensors"
    adapter_map = {}
    for name, item in tensor_header(adapter_file).items():
        match = re.fullmatch(r"(.+)\.(lora_a|lora_b)", name)
        if not match:
            raise ExportError("어댑터에 허용하지 않은 텐서가 있습니다.")
        base = canonical_name(match[1] + ".weight")
        adapter_map[(base, match[2])] = {"file": adapter_file, "name": name, **item}
    targets = {f"model.layers.{text['num_hidden_layers'] - 1}.{key}.weight" for key in params["keys"]}
    if set(adapter_map) != {(name, suffix) for name in targets for suffix in ("lora_a", "lora_b")}:
        raise ExportError("어댑터의 대상 층이 학습 설정과 다릅니다.")
    if output.exists() or output.is_symlink():
        raise ExportError("기존 변환 출력 폴더는 덮어쓰지 않습니다.")
    output.mkdir(mode=0o700)
    mx.set_default_device(mx.cpu)
    def load(entry):
        # MLX 자체 로더로 BF16을 읽어 NumPy의 BF16 자료형 지원 여부에 의존하지 않는다.
        with TensorReader(entry) as reader:
            value = mx.load(reader, format="safetensors")[entry["name"]]
            mx.eval(value)
            return value
    pending, weight_map, total, number = {}, {}, 0, 0
    pending_bytes = 0
    def flush():
        nonlocal pending, pending_bytes, number
        if not pending:
            return
        number += 1
        filename = f"model-{number:05d}.safetensors"
        mx.save_safetensors(str(output / filename), pending, metadata={"format": "pt"})
        for key in pending:
            weight_map[key] = filename
        pending = {}
        pending_bytes = 0
        gc.collect()
        mx.clear_cache()
    for index, name in enumerate(sorted(shapes), 1):
        entry = tensors[name]
        value = load(entry)
        if entry["dtype"] == "U32":
            prefix = name.rsplit(".", 1)[0]
            scales, biases = tensors.get(prefix + ".scales"), tensors.get(prefix + ".biases")
            if scales is None or biases is None:
                raise ExportError("양자화 가중치의 배율과 편향이 누락됐습니다.")
            group, bits, mode = _quantization(config, entry)
            value = mx.dequantize(value, load(scales), load(biases), group_size=group, bits=bits, mode=mode)
        elif entry["dtype"] not in ("F16", "BF16", "F32"):
            raise ExportError("지원하지 않는 기반 가중치 자료형입니다.")
        if name in targets:
            a, b = load(adapter_map[(name, "lora_a")]), load(adapter_map[(name, "lora_b")])
            if a.shape[1] != params["rank"] or b.shape[0] != params["rank"]:
                raise ExportError("어댑터 rank가 학습 설정과 다릅니다.")
            value = merge_lora(value, a, b, params["scale"])
            del a, b
        value = restore_hf_tensor(name, value, layout)
        if tuple(value.shape) != shapes[name]:
            raise ExportError("복원한 가중치의 차원이 기반 모델 설정과 다릅니다.")
        # A_log는 지수 연산의 입력이므로 공식 MLX의 정밀도 보존 규칙을 따른다.
        value = value.astype(mx.float32 if name.endswith(".A_log") else mx.float16)
        mx.eval(value)
        if not mx.all(mx.isfinite(value)).item():
            raise ExportError("복원한 가중치에 유효하지 않은 값이 있습니다.")
        size = value.size * value.itemsize
        if pending and pending_bytes + size > 256 * 1024**2:
            flush()
        pending[name] = value
        pending_bytes += size
        total += size
        del value
        if pending_bytes >= 256 * 1024**2:
            flush()
        progress("FUSING", 5 + index * 45 // len(shapes))
    flush()
    canonical = dict(text)
    for key in ("quantization", "quantization_config", "vision_config", "text_config", "_name_or_path"):
        canonical.pop(key, None)
    canonical.update({"model_type": "qwen3_5", "architectures": ["Qwen3_5ForCausalLM"], "torch_dtype": "float16"})
    for name, value in (("config.json", canonical), ("model.safetensors.index.json", {"metadata": {"total_size": total}, "weight_map": weight_map})):
        (output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for filename in TOKENIZER_FILES:
        source = model / filename
        if source.exists():
            if source.is_symlink() or not source.is_file() or source.stat().st_size > 64 * 1024**2:
                raise ExportError("토크나이저 파일 형식과 크기를 확인하세요.")
            shutil.copyfile(source, output / filename)
    if not (output / "tokenizer.json").is_file() or not (output / "tokenizer_config.json").is_file():
        raise ExportError("변환에 필요한 토크나이저 파일이 없습니다.")
    return {"tensors": len(shapes), "sourceLayout": layout}
