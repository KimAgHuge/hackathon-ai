from fastapi import APIRouter, Depends, HTTPException, status

from app.dependencies import get_llm_service, require_internal_api_key
from app.models.analysis import ModelHealthResponse
from app.services.llm_service import (
    LlmService,
    ModelTimeoutError,
    ModelUnavailableError,
)

router = APIRouter()


@router.get("/health")
def health():
    """프로세스 생존 상태를 반환한다; Ollama 연결 여부는 검사하지 않는다."""
    return {
        "status": "UP",
        "service": "doenaai-ai-server",
        "runtime": "Mac Studio",
    }


@router.get(
    "/api/models/health",
    response_model=ModelHealthResponse,
    dependencies=[Depends(require_internal_api_key)],
)
async def model_health(
    llm_service: LlmService = Depends(get_llm_service),
) -> ModelHealthResponse:
    """내부 키 확인 후 Ollama에서 설정 모델을 조회해 사용 가능 여부를 반환한다."""
    try:
        return await llm_service.health()
    except ModelTimeoutError as exc:
        raise HTTPException(
            status_code=status.HTTP_504_GATEWAY_TIMEOUT,
            detail={"code": "MODEL_TIMEOUT", "message": str(exc)},
        ) from exc
    except ModelUnavailableError as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"code": "MODEL_UNAVAILABLE", "message": str(exc)},
        ) from exc
