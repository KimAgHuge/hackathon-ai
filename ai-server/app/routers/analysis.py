from __future__ import annotations

import asyncio
import json
from contextlib import suppress

from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.responses import StreamingResponse

from app.dependencies import get_llm_service, require_internal_api_key
from app.models.analysis import ProjectAnalysisRequest, ProjectAnalysisResponse
from app.services.llm_service import (
    InvalidModelResponseError,
    LlmService,
    ModelTimeoutError,
    ModelUnavailableError,
)

STREAM_HEARTBEAT_SECONDS = 15

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


@router.post(
    "/project/stream",
    dependencies=[Depends(require_internal_api_key)],
)
async def analyze_project_stream(
    request: ProjectAnalysisRequest,
    llm_service: LlmService = Depends(get_llm_service),
) -> StreamingResponse:
    """안전한 단계 코드와 최종 JSON만 NDJSON으로 전송한다. 원본 프롬프트·모델 출력은 중간 전송하지 않는다."""
    async def events():
        queue: asyncio.Queue[dict] = asyncio.Queue()

        async def report(code: str, attempt: int | None) -> None:
            event = {"type": "progress", "code": code}
            if attempt is not None:
                event["attempt"] = attempt
            await queue.put(event)

        task = asyncio.create_task(llm_service.analyze(request, progress=report))
        try:
            while not task.done() or not queue.empty():
                try:
                    event = await asyncio.wait_for(queue.get(), timeout=STREAM_HEARTBEAT_SECONDS)
                except asyncio.TimeoutError:
                    if task.done():
                        break
                    event = {"type": "heartbeat"}
                yield json.dumps(event, ensure_ascii=False) + "\n"
            try:
                result = await task
            except ModelTimeoutError:
                event = {"type": "error", "code": "MODEL_TIMEOUT"}
            except ModelUnavailableError:
                event = {"type": "error", "code": "MODEL_UNAVAILABLE"}
            except InvalidModelResponseError:
                event = {"type": "error", "code": "INVALID_MODEL_RESPONSE"}
            except Exception:
                event = {"type": "error", "code": "ANALYSIS_FAILED"}
            else:
                event = {"type": "result", "result": result.model_dump(mode="json", by_alias=True)}
            yield json.dumps(event, ensure_ascii=False, separators=(",", ":")) + "\n"
        finally:
            if not task.done():
                task.cancel()
                with suppress(asyncio.CancelledError):
                    await task

    return StreamingResponse(events(), media_type="application/x-ndjson")
