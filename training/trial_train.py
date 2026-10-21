"""승인된 일반 분석 자료로 짧게 학습하고 새 어댑터의 저장·재로딩을 검증한다."""

import argparse
import hashlib
import importlib.metadata
import json
import math
import os
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("HF_HOME", str(ROOT / ".cache/huggingface"))
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")

from dataset_contract import DatasetError, digest, prompt_contract, read_jsonl
from trial_dataset import tokenize_messages


def file_hash(path):
    """작은 설정·어댑터 파일의 변경 여부를 확인한다."""
    checksum = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            checksum.update(chunk)
    return checksum.hexdigest()


def protected_files(model, parent_adapter=None):
    """원본 가중치의 크기·수정 시각과 기존 어댑터의 해시를 보존한다."""
    result = {"model": {p.name: [p.stat().st_size, p.stat().st_mtime_ns]
                      for p in model.iterdir() if p.is_file()},
            "existingAdapter": {str(p.relative_to(ROOT / "adapters")): file_hash(p)
                                for p in (ROOT / "adapters/compatibility-only").rglob("*") if p.is_file()}}
    if parent_adapter is not None:
        result["parentAdapter"] = {name: file_hash(parent_adapter / name)
                                   for name in ("adapters.safetensors", "adapter_config.json")}
    return result


def main():
    """GPU는 명시적으로 실행한 프로세스에서만 사용하고 기존 저장 경로는 거부한다."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("train", "reload"))
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--adapter", type=Path, required=True)
    parser.add_argument("--model", type=Path, default=ROOT / "models/Qwen3.8-27B-4bit")
    parser.add_argument("--iters", type=int, default=10)
    parser.add_argument("--layers", type=int, default=1)
    parser.add_argument("--resume-adapter", type=Path)
    args = parser.parse_args()
    if not 1 <= args.iters <= 20 or args.layers != 1:
        raise DatasetError("이 메모리 절감 시험은 1~20회 업데이트와 마지막 1개 층만 허용합니다.")
    if args.adapter.resolve().parent != (ROOT / "adapters").resolve() or not args.adapter.name.startswith("trial-"):
        raise DatasetError("새 어댑터는 training/adapters/trial-로 시작하는 별도 폴더에 저장하세요.")
    lora = {"rank": 4, "scale": 8.0, "dropout": 0.0,
            "keys": ["mlp.gate_proj", "mlp.up_proj", "mlp.down_proj"]}
    if args.resume_adapter is not None:
        parent = args.resume_adapter
        if (args.phase != "train" or parent.is_symlink() or parent.parent.is_symlink()
                or parent.resolve().parent != (ROOT / "adapters").resolve()
                or not parent.name.startswith("trial-web-") or parent.resolve() == args.adapter.resolve()
                or any((parent / name).is_symlink() or not (parent / name).is_file()
                       for name in ("adapters.safetensors", "adapter_config.json"))):
            raise DatasetError("이전 작업의 어댑터를 별도 경로에서 읽어야 합니다.")
        config = json.loads((parent / "adapter_config.json").read_text())
        if config.get("fine_tune_type") != "lora" or config.get("num_layers") != 1 or config.get("lora_parameters") != lora:
            raise DatasetError("이전 어댑터가 현재 Qwen 시험 학습 설정과 다릅니다.")
    manifest = json.loads((args.dataset / "manifest.json").read_text())
    if prompt_contract()[1] != manifest["contractSha256"]:
        raise DatasetError("자료 생성 후 프롬프트·입력 계약이 변경됐습니다.")
    import mlx.core as mx
    import mlx.nn as nn
    import mlx.optimizers as optim
    from mlx.utils import tree_flatten
    from mlx_lm import load
    from mlx_lm.tuner.trainer import default_loss
    from mlx_lm.tuner.utils import linear_to_lora_layers
    from mlx_lm.models.base import create_attention_mask, create_ssm_mask

    mx.set_memory_limit(48 * 2**30)
    mx.set_cache_limit(1024**3)
    mx.random.seed(0)
    started = time.perf_counter()
    report_path = args.dataset / ("training-report.json" if args.phase == "train" else "reload-report.json")
    if report_path.exists():
        raise DatasetError("기존 시험 보고서는 덮어쓰지 않습니다.")
    before_files = protected_files(args.model, args.resume_adapter)
    print(json.dumps({"phase": "loading", "action": args.phase}), flush=True)
    if args.phase == "train":
        args.adapter.mkdir(mode=0o700, exist_ok=False)
    elif not (args.adapter / "adapters.safetensors").is_file():
        raise DatasetError("재로딩할 새 어댑터가 없습니다.")
    adapter_path = args.adapter if args.phase == "reload" else args.resume_adapter
    model, tokenizer = load(str(args.model), adapter_path=str(adapter_path) if adapter_path is not None else None)
    print(json.dumps({"phase": "loaded", "seconds": time.perf_counter() - started}), flush=True)
    if model.model_type != "qwen3_5" or model.language_model.args.tie_word_embeddings:
        raise DatasetError("정답 위치만 계산하는 시험 손실은 확인한 qwen3_5 구조에서만 지원합니다.")

    def answer_loss(current, batch, offset):
        """입력 전체를 처리하고 손실에 쓰는 정답 위치에만 어휘 투영을 적용한다."""
        hidden = current.model(batch[:, :-1])
        logits = current.language_model.lm_head(hidden[:, offset - 1:, :])
        losses = nn.losses.cross_entropy(logits, batch[:, offset:])
        return losses.astype(mx.float32).mean()

    def frozen_context(batch, offset):
        """변하지 않는 앞부분과 마지막 어텐션은 전체 문맥을 계산해 한 번만 보관한다."""
        base = model.model
        hidden = base.embed_tokens(batch[:, :-1])
        attention_mask = create_attention_mask(hidden, None)
        ssm_mask = create_ssm_mask(hidden, None)
        for layer in model.layers[:-1]:
            hidden = layer(hidden, mask=ssm_mask if layer.is_linear else attention_mask, cache=None)
            mx.eval(hidden)
            mx.clear_cache()
        last = model.layers[-1]
        if last.is_linear:
            raise DatasetError("마지막 층이 확인한 일반 어텐션 구조가 아닙니다.")
        hidden = hidden + last.self_attn(last.input_layernorm(hidden), mask=attention_mask, cache=None)
        # MLP와 이후 정규화는 위치별 연산이므로 정답 위치의 문맥 표현만 남겨도 손실은 같다.
        context = mx.stop_gradient(hidden[:, offset - 1:, :])
        targets = batch[:, offset:]
        mx.eval(context, targets)
        mx.clear_cache()
        return context, targets

    def cached_loss(current, context, targets):
        """전체 입력에서 계산한 고정 문맥에 마지막 MLP만 적용해 같은 정답 손실을 구한다."""
        last = current.layers[-1]
        hidden = context + last.mlp(last.post_attention_layernorm(context))
        logits = current.language_model.lm_head(current.model.norm(hidden))
        return nn.losses.cross_entropy(logits, targets).astype(mx.float32).mean()

    samples = {}
    for split in ("train", "valid", "test"):
        path = args.dataset / f"{split}.jsonl"
        rows = read_jsonl(path) if path.stat().st_size else []
        samples[split] = []
        for row in rows:
            tokens, offset = tokenize_messages(tokenizer, row["messages"])
            if tokens != row["tokens"] or offset != row["offset"] or len(tokens) > manifest["maxTokens"]:
                raise DatasetError("자료의 토큰·정답 경계·길이가 준비 당시와 다릅니다.")
            samples[split].append((mx.array([tokens]), offset))
    probe = mx.array([[1, 2, 3, 4, 5, 6, 7, 8]])
    if args.phase == "reload":
        reference = json.loads((args.dataset / "training-report.json").read_text())
        if file_hash(args.adapter / "adapters.safetensors") != reference["adapterSha256"]:
            raise DatasetError("학습 후 어댑터가 변경됐습니다.")
        saved = mx.load(str(args.adapter / "adapters.safetensors"))
        current = dict(tree_flatten(model.parameters()))
        if not all(key in current and mx.array_equal(current[key], value).item() for key, value in saved.items()):
            raise DatasetError("어댑터 재로딩 텐서가 저장값과 다릅니다.")
        model.eval()
        logits = model(probe)[:, -1, :256].astype(mx.float32)
        difference = mx.max(mx.abs(logits - mx.array(reference["probeLogits"]))).item()
        if not math.isfinite(difference) or difference > 0.02:
            raise DatasetError("새 프로세스의 재로딩 출력이 기준과 다릅니다.")
        result = {"status": "complete", "adapterTensors": len(saved), "maxLogitDifference": difference,
                  "seconds": time.perf_counter() - started, "peakGpuGiB": mx.get_peak_memory() / 2**30}
        report_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps(result, ensure_ascii=False), flush=True)
        return

    model.freeze()
    # 긴 입력의 어텐션 역전파 메모리를 피하고 마지막 층의 위치별 MLP만 학습한다.
    if args.resume_adapter is None:
        linear_to_lora_layers(model, args.layers, lora)
    else:
        # 부모 가중치를 읽은 뒤 어댑터를 다시 붙이지 않고 기존 LoRA 텐서만 갱신한다.
        model.unfreeze(keys=["lora_a", "lora_b"])
    trainable = dict(tree_flatten(model.trainable_parameters()))
    if not trainable or any(not key.endswith(("lora_a", "lora_b")) or ".mlp." not in key for key in trainable):
        raise DatasetError("학습 대상이 어댑터 파라미터로 제한되지 않았습니다.")
    original = {key: mx.array(value) for key, value in trainable.items()}
    mx.eval(original)
    model.train()
    # 고정된 앞부분은 미분할 파라미터가 없으므로 빠른 선형 어텐션 추론 커널을 사용한다.
    # 실제 학습 대상인 마지막 층은 학습 모드를 유지하고 모든 입력 토큰을 처리한다.
    for layer in model.layers[:-args.layers]:
        layer.eval()
    # 짧은 입력에서 공식 마스킹 손실과 값·gradient가 같은지 먼저 확인한다.
    official = nn.value_and_grad(model, lambda current: default_loss(current, probe, mx.array([[4, 7]]))[0])
    reduced = nn.value_and_grad(model, lambda current: answer_loss(current, probe, 4))
    left, left_grad = official(model)
    right, right_grad = reduced(model)
    left_grad, right_grad = dict(tree_flatten(left_grad)), dict(tree_flatten(right_grad))
    loss_difference = mx.abs(left - right).item()
    gradient_difference = max(mx.max(mx.abs(left_grad[k] - right_grad[k])).item() for k in left_grad)
    context, targets = frozen_context(probe, 4)
    cached_value_grad = nn.value_and_grad(model, cached_loss)
    cached_value, cached_grad = cached_value_grad(model, context, targets)
    cached_grad = dict(tree_flatten(cached_grad))
    cache_loss_difference = mx.abs(left - cached_value).item()
    cache_gradient_difference = max(mx.max(mx.abs(left_grad[k] - cached_grad[k])).item() for k in left_grad)
    if (not math.isfinite(loss_difference) or not math.isfinite(gradient_difference)
            or not math.isfinite(cache_loss_difference) or not math.isfinite(cache_gradient_difference)
            or max(loss_difference, gradient_difference, cache_loss_difference, cache_gradient_difference) > 0.0001):
        raise DatasetError("메모리를 줄인 손실이 공식 손실과 일치하지 않습니다.")
    del left, right, left_grad, right_grad, official, reduced, cached_value, cached_grad
    mx.clear_cache()
    print(json.dumps({"phase": "loss_equivalence_verified", "lossDifference": loss_difference,
                      "gradientDifference": gradient_difference, "cachedLossDifference": cache_loss_difference,
                      "cachedGradientDifference": cache_gradient_difference}), flush=True)
    optimizer = optim.Adam(learning_rate=1e-5)
    cached_samples = {"train": [], "valid": []}
    for split in cached_samples:
        for batch, offset in samples[split]:
            print(json.dumps({"phase": "frozen_context", "split": split, "tokens": batch.shape[1]}), flush=True)
            cached_samples[split].append(frozen_context(batch, offset))
    loss_grad = nn.value_and_grad(model, cached_loss)
    baseline = {}
    for split in ("valid",):
        print(json.dumps({"phase": "validation_before", "samples": len(samples[split])}), flush=True)
        values = [cached_loss(model, context, targets).item() for context, targets in cached_samples[split]]
        if any(not math.isfinite(value) for value in values):
            raise DatasetError("학습 전 검증 손실이 유한하지 않습니다.")
        baseline[split] = sum(values) / len(values) if values else None
    rows = []
    print(json.dumps({"phase": "train", "sampleCounts": {k: len(v) for k, v in samples.items()},
                      "trainableParameters": sum(v.size for v in trainable.values()),
                      "lossEquivalenceMaxDifference": loss_difference,
                      "gradientEquivalenceMaxDifference": gradient_difference}, ensure_ascii=False), flush=True)
    for step in range(args.iters):
        context, targets = cached_samples["train"][step % len(cached_samples["train"])]
        loss, grads = loss_grad(model, context, targets)
        norm = mx.sqrt(sum(mx.sum(g.astype(mx.float32) ** 2) for _, g in tree_flatten(grads)))
        mx.eval(loss, norm)
        loss_value, norm_value = loss.item(), norm.item()
        if not math.isfinite(loss_value) or not math.isfinite(norm_value) or norm_value <= 0:
            raise DatasetError("손실값 또는 gradient가 유효하지 않아 학습을 중단합니다.")
        optimizer.update(model, grads)
        mx.eval(model.trainable_parameters(), optimizer.state)
        item = {"step": step + 1, "loss": loss_value, "gradientNorm": norm_value,
                "seconds": time.perf_counter() - started, "peakGpuGiB": mx.get_peak_memory() / 2**30}
        rows.append(item)
        print(json.dumps(item), flush=True)
        with (args.dataset / "training-steps.jsonl").open("a") as target:
            target.write(json.dumps(item) + "\n")
        del loss, grads, norm
        mx.clear_cache()
    current = dict(tree_flatten(model.trainable_parameters()))
    delta = max(mx.max(mx.abs(current[key] - original[key])).item() for key in current)
    if not math.isfinite(delta) or delta <= 0:
        raise DatasetError("학습 후 어댑터 변화가 없습니다.")
    mx.save_safetensors(str(args.adapter / "adapters.safetensors"), current)
    (args.adapter / "adapter_config.json").write_text(json.dumps({"fine_tune_type": "lora", "num_layers": args.layers,
        "lora_parameters": lora, "purpose": "approved_case_smoke_test_do_not_deploy"}, ensure_ascii=False, indent=2) + "\n")
    after = {}
    for split in ("valid",):
        values = [cached_loss(model, context, targets).item() for context, targets in cached_samples[split]]
        if any(not math.isfinite(value) for value in values):
            raise DatasetError("학습 후 검증 손실이 유한하지 않습니다.")
        after[split] = sum(values) / len(values) if values else None
    model.eval()
    logits = model(probe)[:, -1, :256].astype(mx.float32)
    mx.eval(logits)
    if protected_files(args.model, args.resume_adapter) != before_files:
        raise DatasetError("원본 모델 또는 기존 어댑터의 보존 검사에 실패했습니다.")
    result = {"status": "complete", "purpose": "approved_case_smoke_test_do_not_deploy",
              "datasetManifestSha256": file_hash(args.dataset / "manifest.json"), "contractSha256": manifest["contractSha256"],
              "model": manifest["model"], "versions": {p: importlib.metadata.version(p) for p in ("mlx", "mlx-lm", "transformers")},
              "iterations": args.iters, "layers": args.layers, "rank": 4, "learningRate": 1e-5, "thinking": False,
              "trainableModules": lora["keys"],
              "truncatedSamples": 0, "steps": rows, "validationLossBefore": baseline, "validationLossAfter": after,
              "adapterMaxDelta": delta, "adapterTensors": len(current), "adapterSha256": file_hash(args.adapter / "adapters.safetensors"),
              "lossEquivalenceMaxDifference": loss_difference, "gradientEquivalenceMaxDifference": gradient_difference,
              "cachedLossEquivalenceMaxDifference": cache_loss_difference,
              "cachedGradientEquivalenceMaxDifference": cache_gradient_difference, "frozenContextReused": True,
              "protectedFilesUnchanged": True, "protectedFiles": before_files,
              "probeLogits": logits.tolist(), "seconds": time.perf_counter() - started,
              "peakGpuGiB": mx.get_peak_memory() / 2**30}
    if args.resume_adapter is not None:
        result.update(parentAdapterSha256=before_files["parentAdapter"]["adapters.safetensors"],
                      parentConfigSha256=before_files["parentAdapter"]["adapter_config.json"])
    report_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: result[key] for key in ("status", "iterations", "adapterMaxDelta", "adapterTensors", "seconds", "peakGpuGiB")}), flush=True)


if __name__ == "__main__":
    main()
