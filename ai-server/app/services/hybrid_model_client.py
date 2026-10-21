"""설치 모델과 로컬 MLX 어댑터를 같은 선택·검증 계약으로 호출한다."""

import httpx
from ollama import ResponseError


def as_dict(value):
    """SDK 응답도 내부 관리 응답과 같은 작은 사전 형태로 다룬다."""
    return value if isinstance(value, dict) else value.model_dump()


class HybridModelClient:
    """모델 이름에 고정된 실행 환경으로 라우팅하며 임의 주소는 받지 않는다."""

    def __init__(self, primary, adapters):
        self.primary, self.adapters = primary, adapters

    async def _adapter(self, method, *args, **kwargs):
        from app.services.mlx_adapter_runtime import MlxAdapterError
        from app.services.model_runtime import ModelRuntimeError
        try:
            return await getattr(self.adapters, method)(*args, **kwargs)
        except MlxAdapterError as error:
            raise ModelRuntimeError(error.status, error.code, error.message) from None

    async def list(self):
        local = await self._adapter("list")
        try:
            primary = as_dict(await self.primary.list())
        except (httpx.HTTPError, ResponseError, OSError, ConnectionError):
            if local.get("models"):
                return local
            raise
        return {"models": [*primary.get("models", []), *local.get("models", [])]}

    async def show(self, model):
        if self.adapters.owns(model):
            return await self._adapter("show", model)
        return as_dict(await self.primary.show(model))

    async def chat(self, **body):
        if self.adapters.owns(body.get("model")):
            return await self._adapter("chat", **body)
        return await self.primary.chat(**body)
