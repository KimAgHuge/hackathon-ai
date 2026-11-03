from functools import lru_cache
from typing import Literal

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
    ai_model: str = Field(default="qwen3.8:27b", min_length=1)
    ai_internal_api_key: SecretStr = Field(min_length=32)
    ai_request_timeout_seconds: float = Field(default=900, gt=0)
    ai_keep_alive: str = "30m"
    ai_qwen_thinking_effort: Literal["low", "medium"] = "medium"
    ai_max_validation_retries: int = Field(default=1, ge=0, le=3)
    ai_log_model_requests: bool = False
    ai_db_batch_max_chars: int = Field(default=12000, ge=1000, le=90000)
    ai_db_batch_max_tables: int = Field(default=6, ge=1, le=40)
    ai_checkpoints_enabled: bool = True
    ai_checkpoint_max_age_seconds: int = Field(default=86400, ge=60, le=604800)
    ai_final_reconciliation_enabled: bool = True
    ai_requirement_fast_path_enabled: bool = True
    ai_db_merge_max_chars: int = Field(default=24000, ge=1000, le=90000)
    ai_rag_enabled: bool = True
    # 한글 요구사항과 영어 식별자를 잇는 임베딩 모델. ollama pull bge-m3 가 필요하며, 비우거나 실패하면 낱말 검색만 쓴다.
    ai_embedding_model: str = "bge-m3"
    ai_rag_index_path: str = ""
    ai_mlx_model_dir: str = ""
    ai_mlx_python: str = ""
    ai_mlx_adapter_dir: str = ""
    ai_trained_model_dir: str = ""
    ai_trained_model_import_timeout_seconds: int = Field(default=7200, ge=60, le=21600)
    ai_model_state_file: str = ""
    ai_model_admin_timeout_seconds: float = Field(default=10, gt=0, le=30)
    ai_model_probe_timeout_seconds: float = Field(default=60, gt=0, le=120)
    ai_model_probe_ttl_seconds: int = Field(default=300, ge=30, le=900)
    training_shared_dir: str = ""
    training_receiver_url: str = ""
    training_transfer_key: SecretStr = SecretStr("")


@lru_cache
def get_settings() -> Settings:
    return Settings()
