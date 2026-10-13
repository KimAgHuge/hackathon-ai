from fastapi import APIRouter, Depends, HTTPException, status

from app.dependencies import get_llm_service, require_internal_api_key
from app.models.analysis import ProjectAnalysisRequest, ProjectAnalysisResponse
from app.services.llm_service import (
    InvalidModelResponseError,
    LlmService,
    ModelTimeoutError,
    ModelUnavailableError,
)

router = APIRouter(prefix="/api/analysis", tags=["analysis"])


@router.post(
    "/project",
    response_model=ProjectAnalysisResponse,
    dependencies=[Depends(require_internal_api_key)],
)
async def analyze_project(
    request: ProjectAnalysisRequest,
    llm_service: LlmService = Depends(get_llm_service),
) -> ProjectAnalysisResponse:
    """내부 키로 보호된 분석 API; 수집 근거를 받아 결과 또는 모델 오류 HTTP 응답을 돌려준다."""
    try:
        return await llm_service.analyze(request)
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
    except InvalidModelResponseError as exc:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail={"code": "INVALID_MODEL_RESPONSE", "message": str(exc)},
        ) from exc
