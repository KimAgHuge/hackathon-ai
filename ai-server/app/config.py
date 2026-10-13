from functools import lru_cache

from pydantic import AnyHttpUrl, Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """DOENAAI_ 환경변수 또는 .env에서 Ollama·내부 인증 설정을 읽는다."""
    model_config = SettingsConfigDict(
        env_prefix="DOENAAI_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    ollama_url: AnyHttpUrl = "http://127.0.0.1:11434"
    ai_model: str = Field(default="devstral", min_length=1)
    ai_internal_api_key: SecretStr = Field(min_length=32)
    ai_request_timeout_seconds: float = Field(default=300, gt=0)
    ai_keep_alive: str = "30m"
    ai_max_validation_retries: int = Field(default=1, ge=0, le=3)


@lru_cache
def get_settings() -> Settings:
    return Settings()
