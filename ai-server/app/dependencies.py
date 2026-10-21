import secrets
from functools import lru_cache
from typing import Optional

from fastapi import Depends, Header, HTTPException, status

from app.config import Settings, get_settings
from app.services.llm_service import LlmService
from app.services.model_runtime import ModelRuntimeService


def require_internal_api_key(
    api_key: Optional[str] = Header(default=None, alias="X-Internal-Api-Key"),
    settings: Settings = Depends(get_settings),
) -> None:
    """요청 헤더의 내부 키를 설정값과 비교하고 불일치하면 401을 반환한다."""
    expected_key = settings.ai_internal_api_key.get_secret_value()
    if not api_key or not secrets.compare_digest(api_key, expected_key):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={
                "code": "INVALID_INTERNAL_API_KEY",
                "message": "A valid internal API key is required.",
            },
        )


@lru_cache
def get_model_runtime_service() -> ModelRuntimeService:
    """모델 선택 기록과 관리 클라이언트를 프로세스 안에서 재사용한다."""
    return ModelRuntimeService(get_settings())


@lru_cache
def get_llm_service() -> LlmService:
    """설정에 연결된 Ollama 클라이언트를 프로세스 내에서 재사용한다."""
    return LlmService(get_settings(), runtime=get_model_runtime_service())
