"""Qwen 로딩·역전파·어댑터 재로딩을 검증한다. 합성 대화는 업무 학습에 사용하지 않는다."""

import argparse
import importlib.metadata
import json
import os
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
# 모델은 로컬 파일만 사용하며, 라이브러리 초기화 전에 캐시·오프라인 설정을 적용한다.
os.environ.setdefault("HF_HOME", str(ROOT / ".cache" / "huggingface"))
os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")

import mlx.core as mx
import mlx.nn as nn
import mlx.optimizers as optim
from mlx.utils import tree_flatten
from mlx_lm import load, stream_generate
from mlx_lm.sample_utils import make_sampler
from mlx_lm.tuner.datasets import ChatDataset
from mlx_lm.tuner.trainer import default_loss
from mlx_lm.tuner.utils import linear_to_lora_layers

MODEL = ROOT / "models" / "Qwen3.8-27B-4bit"
OUTPUT = ROOT / "outputs" / "qwen-compatibility"
ADAPTER = ROOT / "adapters" / "compatibility-only"
LORA = {"rank": 4, "scale": 8.0, "dropout": 0.0}


def write_report(phase, data):
    """단계별 측정값과 모델·패키지 버전을 JSON에 저장하고 핵심 결과를 출력한다."""
    OUTPUT.mkdir(parents=True, exist_ok=True)
    data.update({
        "purpose": "infrastructure_only_not_quality_training",
        "phase": phase,
        "model": json.loads((MODEL / "download_manifest.json").read_text()),
        "versions": {p: importlib.metadata.version(p) for p in ("mlx", "mlx-lm", "transformers")},
        "peakGpuGiB": mx.get_peak_memory() / 2**30,
    })
    (OUTPUT / f"{phase}.json").write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
    # 재로딩 비교용 토큰·출력 벡터는 파일에 보관하되 긴 배열을 터미널에 반복 출력하지 않는다.
    print(json.dumps({k: v for k, v in data.items() if k not in {"probeTokenIds", "probeLogits"}},
                     ensure_ascii=False, indent=2), flush=True)


def main():
    """선택한 단계의 GPU 검증을 수행한다. 단계마다 새 프로세스로 실행해야 한다."""
    parser = argparse.ArgumentParser()
    parser.add_argument("phase", choices=["load", "train", "reload"],
                        help="검증 단계: 모델 로딩·추론(load), 역전파·저장(train), 재로딩(reload)")
    phase = parser.parse_args().phase
    mx.random.seed(0)
    started = time.perf_counter()
    print(f"[{phase}] 버전이 고정된 로컬 모델을 불러옵니다.", flush=True)
    model, tokenizer = load(str(MODEL), adapter_path=str(ADAPTER) if phase == "reload" else None)
    print(f"[{phase}] 모델 로딩 완료: {time.perf_counter() - started:.2f}초", flush=True)

    if phase == "load":
        # 이 단문 시험은 실행 여부만 확인한다. 운영 분석의 thinking 설정과는 별개다.
        prompt = tokenizer.apply_chat_template(
            [{"role": "user", "content": '출력 검증입니다. {"ok":true}만 답하세요.'}],
            tokenize=False, add_generation_prompt=True, enable_thinking=False,
        )
        generated = []
        generation_started = time.perf_counter()
        for response in stream_generate(model, tokenizer, prompt, max_tokens=48, sampler=make_sampler(temp=0)):
            generated.append(response.text)
        answer = "".join(generated)
        if not answer.strip():
            raise RuntimeError("모델이 응답 본문을 생성하지 않았습니다.")
        write_report(phase, {
            "pass": True, "loadSeconds": generation_started - started,
            "generationSeconds": time.perf_counter() - generation_started,
            "answer": answer, "finishReason": response.finish_reason,
            "generatedTokens": response.generation_tokens,
            "thinking": False, "maxTokens": 48,
            "quantization": json.loads((MODEL / "config.json").read_text())["quantization"],
            "layers": len(model.layers),
        })
        return

    if phase == "reload":
        # 새로 로드한 어댑터의 텐서와 동일 입력의 출력 일부를 저장 당시 결과와 대조한다.
        reference = json.loads((OUTPUT / "train.json").read_text())
        model.eval()
        logits = model(mx.array([reference["probeTokenIds"]]))[0, -1, :256]
        mx.eval(logits)
        expected = mx.array(reference["probeLogits"])
        difference = mx.max(mx.abs(logits.astype(mx.float32) - expected)).item()
        if difference > 0.02:
            raise RuntimeError(f"재로딩한 모델의 출력값이 다릅니다. 최대 차이: {difference}")
        tensors = mx.load(str(ADAPTER / "adapters.safetensors"))
        current = dict(tree_flatten(model.parameters()))
        for key, value in tensors.items():
            if not mx.array_equal(current[key], value).item():
                raise RuntimeError(f"어댑터 텐서가 저장값과 일치하지 않습니다: {key}")
        write_report(phase, {"pass": True, "seconds": time.perf_counter() - started,
                             "adapterTensors": len(tensors), "maxLogitDifference": difference})
        return

    # 합성 대화 한 건으로 토큰 분리와 역전파만 시험한다. 제품 분석의 학습 정답이 아니다.
    messages = [
        {"role": "user", "content": "검증용입니다. 숫자 1을 답하세요."},
        {"role": "assistant", "content": "1"},
    ]
    data = ChatDataset([{"messages": messages}], tokenizer, mask_prompt=True)
    token_ids, offset = data.process(data[0])
    if len(token_ids) > 256 or offset >= len(token_ids) - 1:
        raise RuntimeError(f"대화의 학습 대상 범위가 잘못됐습니다: 토큰 수={len(token_ids)}, 시작 위치={offset}")
    # 원본 가중치를 고정하고 마지막 4개 층의 LoRA 파라미터만 학습 대상으로 만든다.
    model.freeze()
    linear_to_lora_layers(model, 4, LORA)
    trainable = dict(tree_flatten(model.trainable_parameters()))
    if not trainable or any(not k.endswith(("lora_a", "lora_b")) for k in trainable):
        raise RuntimeError("학습 대상이 없거나 어댑터 외의 원본 파라미터가 포함됐습니다.")
    quantized = sum(isinstance(m, nn.QuantizedLinear) for _, m in model.named_modules())
    if not quantized:
        raise RuntimeError("양자화된 원본 선형층을 찾지 못했습니다.")
    before = {k: mx.array(v) for k, v in trainable.items()}
    mx.eval(before)
    optimizer = optim.Adam(learning_rate=1e-5)
    loss_and_grad = nn.value_and_grad(model, default_loss)
    losses, gradient_norms = [], []
    batch = mx.array([token_ids])
    lengths = mx.array([[offset, len(token_ids) - 1]])
    model.train()
    for step in range(2):
        (loss, supervised), grads = loss_and_grad(model, batch, lengths)
        flat_grads = [g for _, g in tree_flatten(grads)]
        grad_norm = mx.sqrt(sum(mx.sum(g.astype(mx.float32) ** 2) for g in flat_grads))
        finite = mx.isfinite(loss) & mx.isfinite(grad_norm)
        mx.eval(loss, grad_norm, finite)
        if not finite.item() or grad_norm.item() <= 0 or supervised.item() <= 0:
            raise RuntimeError("손실값·gradient 또는 학습 대상 토큰 수가 유효하지 않습니다.")
        optimizer.update(model, grads)
        mx.eval(model.trainable_parameters(), optimizer.state)
        losses.append(loss.item())
        gradient_norms.append(grad_norm.item())
        print(f"[train] 업데이트 {step + 1}: 손실값={loss.item():.6f}, gradient 크기={grad_norm.item():.6f}", flush=True)
    current = dict(tree_flatten(model.trainable_parameters()))
    delta = max(mx.max(mx.abs(current[k] - before[k])).item() for k in current)
    if delta <= 0:
        raise RuntimeError("업데이트 후 어댑터 파라미터가 변하지 않았습니다.")
    # 시험용 어댑터만 저장한다. 원본 모델 파일은 덮어쓰지 않는다.
    ADAPTER.mkdir(parents=True, exist_ok=True)
    mx.save_safetensors(str(ADAPTER / "adapters.safetensors"), current)
    (ADAPTER / "adapter_config.json").write_text(json.dumps({
        "fine_tune_type": "lora", "num_layers": 4, "lora_parameters": LORA,
        "purpose": "infrastructure_only_do_not_deploy",
    }, indent=2) + "\n")
    model.eval()
    # 다음 프로세스의 재로딩 검증에 사용할 동일 입력과 출력 일부를 기록한다.
    probe = token_ids[:offset]
    logits = model(mx.array([probe]))[0, -1, :256]
    mx.eval(logits)
    write_report(phase, {
        "pass": True, "seconds": time.perf_counter() - started,
        "steps": 2, "layers": 4, "rank": 4, "batchSize": 1,
        "adaptedLayerTypes": ["linear_attention" if layer.is_linear else "full_attention"
                              for layer in model.layers[-4:]],
        "sequenceLength": len(token_ids), "maskedPromptTokens": offset,
        "trainableParameters": sum(v.size for v in current.values()),
        "quantizedLinearLayers": quantized, "losses": losses,
        "gradientNorms": gradient_norms, "maxAdapterUpdate": delta,
        "adapterBytes": (ADAPTER / "adapters.safetensors").stat().st_size,
        "probeTokenIds": probe, "probeLogits": logits.tolist(),
        "thinking": "tokenizer default in chat-format backward smoke test",
    })


if __name__ == "__main__":
    main()
