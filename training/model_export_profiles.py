"""학습 지원과 별도로 검토한 모델별 GGUF 변환 경로를 판정한다."""

import re

from training_profiles import QWEN_PROFILE, ProfileError, validate_profile

QWEN_BASE = "mlx-community/Qwen3.8-27B-4bit"
QWEN_REVISION = "10c35caafbb80f7dc6a7a432cdd11af10a6d4818"
GENERIC_EXPORTER = "mlx-lm-hf-gguf-v1"
# 고정 MLX-LM의 저장 레이아웃과 고정 llama.cpp 변환기 양쪽을 확인한 밀집 텍스트 모델만 허용한다.
ARCHITECTURES = {"llama": "llama", "mistral": "llama", "qwen2": "qwen2", "qwen3": "qwen3", "exaone4": "exaone4"}
HF_ARCHITECTURES = {"llama": "LlamaForCausalLM", "mistral": "MistralForCausalLM", "qwen2": "Qwen2ForCausalLM",
                    "qwen3": "Qwen3ForCausalLM", "exaone4": "Exaone4ForCausalLM"}
LINEAR_KEYS = {"self_attn.q_proj", "self_attn.k_proj", "self_attn.v_proj", "self_attn.o_proj",
               "mlp.gate_proj", "mlp.up_proj", "mlp.down_proj"}
REVISION = re.compile(r"[0-9a-f]{40}\Z")
MODEL_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*\Z")


def export_capability(profile, *, base_model=None, revision=None):
    """프로필과 기반 버전에 허용한 실행기가 있을 때만 변환을 허용한다."""
    failure = {"supported": False, "reasonCode": "MODEL_BUILD_UNSUPPORTED_PROFILE",
               "message": "이 모델의 학습은 가능하지만 등록한 프로필에 지원하는 GGUF 변환 경로가 없습니다."}
    try:
        profile = validate_profile(profile)
    except ProfileError:
        return {**failure, "message": "모델별 학습 프로필을 확인한 뒤 변환 경로를 등록하세요."}
    if ((base_model is not None and (not isinstance(base_model, str) or not MODEL_ID.fullmatch(base_model)))
            or (revision is not None and (not isinstance(revision, str) or not REVISION.fullmatch(revision)))):
        return {**failure, "message": "변환할 기반 모델의 정확한 식별자와 고정 버전을 확인하세요."}
    exporter, architecture = profile.get("exporter"), profile.get("exportArchitecture")
    if exporter == "qwen-gguf-v1":
        if (profile != QWEN_PROFILE or base_model not in (None, QWEN_BASE)
                or revision not in (None, QWEN_REVISION)):
            return {**failure, "message": "기존 Qwen 변환기는 검증한 학습 설정과 기반 모델 버전에만 사용할 수 있습니다."}
    elif (exporter != GENERIC_EXPORTER or ARCHITECTURES.get(profile["modelType"]) != architecture
          or not set(profile["loraKeys"]) <= LINEAR_KEYS):
        return failure
    return {"supported": True, "reasonCode": "READY", "architecture": architecture,
            "message": "등록한 모델 구조에 대응하는 고정 GGUF 변환 경로를 사용할 수 있습니다."}
