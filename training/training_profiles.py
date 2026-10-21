"""서버에 등록한 모델별 학습 설정을 실행 전에 엄격하게 확인한다."""

import math
import re

GENERIC_TRAINER = "mlx-lora-v1"
QWEN_PROFILE = {
    "modelType": "qwen3_5", "loraLayers": 1, "loraRank": 4, "loraScale": 8.0,
    "loraDropout": 0.0, "loraKeys": ["mlp.gate_proj", "mlp.up_proj", "mlp.down_proj"],
    "maxSequenceLength": 18000, "batchSize": 1, "learningRate": 1e-5,
    "gradientCheckpointing": False, "exportArchitecture": "qwen35", "exporter": "qwen-gguf-v1",
}
REQUIRED = {"modelType", "loraLayers", "loraRank", "loraScale", "loraDropout", "loraKeys",
            "maxSequenceLength", "batchSize", "learningRate", "gradientCheckpointing"}
OPTIONAL = {"exportArchitecture", "exporter"}
IDENTIFIER = re.compile(r"[a-z][a-z0-9_-]{0,79}\Z")
MODULE_KEY = re.compile(r"[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*\Z")


class ProfileError(ValueError):
    """설정 원문이나 로컬 경로를 공개하지 않는 학습 설정 오류다."""


def validate_profile(value):
    """지원하는 설정 키와 자료형·자원 상한을 확인하고 새 사본을 반환한다."""
    if not isinstance(value, dict) or not REQUIRED <= value.keys() or value.keys() - REQUIRED - OPTIONAL:
        raise ProfileError("학습 프로필의 필수 항목과 허용된 설정 키를 확인하세요.")
    if not isinstance(value["modelType"], str) or not IDENTIFIER.fullmatch(value["modelType"]):
        raise ProfileError("학습 프로필의 모델 구조 식별자를 확인하세요.")
    for key, lower, upper in (("loraLayers", 1, 128), ("loraRank", 1, 128),
                              ("maxSequenceLength", 32, 32768), ("batchSize", 1, 4)):
        if type(value[key]) is not int or not lower <= value[key] <= upper:
            raise ProfileError("학습 프로필의 정수 설정이 허용 범위를 벗어났습니다.")
    for key, lower, upper in (("loraScale", 0.0, 256.0), ("loraDropout", 0.0, 0.5),
                              ("learningRate", 1e-7, 1e-2)):
        number = value[key]
        if (type(number) not in (int, float) or not math.isfinite(number)
                or not lower <= number <= upper or (key == "loraScale" and number == 0)):
            raise ProfileError("학습 프로필의 소수 설정이 허용 범위를 벗어났습니다.")
    keys = value["loraKeys"]
    if (not isinstance(keys, list) or not 1 <= len(keys) <= 64
            or any(not isinstance(key, str) or len(key) > 200 or not MODULE_KEY.fullmatch(key) for key in keys)
            or len(set(keys)) != len(keys)):
        raise ProfileError("LoRA 대상은 중복 없는 실제 모듈 경로로 지정하세요.")
    if type(value["gradientCheckpointing"]) is not bool:
        raise ProfileError("메모리 절감 설정은 참 또는 거짓이어야 합니다.")
    for key in OPTIONAL & value.keys():
        if not isinstance(value[key], str) or not IDENTIFIER.fullmatch(value[key]):
            raise ProfileError("모델 변환 설정은 서버의 실행기 식별자만 사용할 수 있습니다.")
    normalized = {**value, "loraKeys": list(keys)}
    for key in ("loraScale", "loraDropout", "learningRate"):
        normalized[key] = float(value[key])
    return normalized


def lora_parameters(profile):
    """공식 MLX-LM이 받는 LoRA 설정으로 변환한다."""
    profile = validate_profile(profile)
    return {"rank": profile["loraRank"], "scale": profile["loraScale"],
            "dropout": profile["loraDropout"], "keys": list(profile["loraKeys"])}
