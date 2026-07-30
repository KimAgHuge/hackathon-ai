import secrets
from functools import lru_cache
from typing import Optional

from fastapi import Depends, Header, HTTPException, status

from app.config import Settings, get_settings
from app.services.llm_service import LlmService


def require_internal_api_key(
    api_key: Optional[str] = Header(default=None, alias="X-Internal-Api-Key"),
    settings: Settings = Depends(get_settings),
) -> None:
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
def get_llm_service() -> LlmService:
    return LlmService(get_settings())
