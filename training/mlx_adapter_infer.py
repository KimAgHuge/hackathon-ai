"""고정된 로컬 기반 모델과 어댑터로 한 요청만 처리한 뒤 종료한다."""

import argparse
import importlib.util
import json
import os
import platform
from pathlib import Path


def write_result(path, value):
    """추론 결과를 다른 사용자가 읽을 수 없는 새 파일에만 기록한다."""
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as target:
        json.dump(value, target, ensure_ascii=False, allow_nan=False)


def infer(model_path, adapter_path, request):
    """검증은 호출자가 수행하며 이 프로세스는 모델 답변과 종료 이유를 그대로 돌려준다."""
    import mlx.core as mx
    from mlx_lm import load, stream_generate
    from mlx_lm.sample_utils import make_sampler

    mx.set_memory_limit(48 * 2**30)
    mx.set_cache_limit(1024**3)
    mx.random.seed(0)
    model, tokenizer = load(str(model_path), adapter_path=str(adapter_path))
    messages = [dict(message) for message in request["messages"]]
    instruction = "다음 JSON Schema를 따르는 JSON 객체만 출력하세요. 설명이나 코드 블록을 덧붙이지 마세요.\n" + json.dumps(
        request["schema"], ensure_ascii=False, separators=(",", ":"))
    if messages[0]["role"] == "system":
        messages[0]["content"] += "\n\n" + instruction
    else:
        messages.insert(0, {"role": "system", "content": instruction})
    prompt = tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=True, enable_thinking=False)
    if len(tokenizer.encode(prompt)) > 32768:
        raise ValueError("추론 입력이 허용된 토큰 수를 초과했습니다.")
    fragments = []
    final = None
    for response in stream_generate(model, tokenizer, prompt, max_tokens=request["maxTokens"], sampler=make_sampler(temp=0)):
        fragments.append(response.text)
        final = response
    if final is None or final.finish_reason not in {"stop", "length"}:
        raise ValueError("추론 종료 상태를 확인하지 못했습니다.")
    return {"content": "".join(fragments), "finishReason": final.finish_reason,
            "promptTokens": final.prompt_tokens, "generationTokens": final.generation_tokens}


def main():
    """경로와 원문은 출력하지 않고 결과 파일 또는 실패 종료 코드만 남긴다."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--adapter", type=Path, required=True)
    parser.add_argument("--request", type=Path, required=True)
    parser.add_argument("--response", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    arguments = parser.parse_args()
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    os.environ["TOKENIZERS_PARALLELISM"] = "false"
    if arguments.check:
        available = (platform.system() == "Darwin" and platform.machine() == "arm64"
                     and all(importlib.util.find_spec(name) is not None for name in ("mlx", "mlx_lm", "transformers")))
        write_result(arguments.response, {"available": available})
        return
    if arguments.request.is_symlink() or arguments.request.stat().st_size > 2 * 1024 * 1024:
        raise ValueError("추론 요청 파일을 확인하지 못했습니다.")
    request = json.loads(arguments.request.read_text(encoding="utf-8"))
    write_result(arguments.response, infer(arguments.model, arguments.adapter, request))


if __name__ == "__main__":
    main()
