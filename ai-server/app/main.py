import logging
import re
import time
from uuid import uuid4

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.routers import analysis, health

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger("doenaai.request")
SAFE_REQUEST_ID_PATTERN = re.compile(r"^[A-Za-z0-9._-]{1,128}$")


def create_app() -> FastAPI:
    """분석·상태 라우터와 안전한 오류/요청 로그를 등록한 FastAPI 앱을 만든다."""
    application = FastAPI(title="doenaai ai server", version="0.1.0")
    application.include_router(health.router)
    application.include_router(analysis.router)

    @application.exception_handler(RequestValidationError)
    async def validation_exception_handler(
        request: Request,
        exc: RequestValidationError,
    ):
        safe_errors = [
            {
                "loc": error.get("loc", ()),
                "msg": error.get("msg", "Invalid request."),
                "type": error.get("type", "value_error"),
            }
            for error in exc.errors()
        ]
        return JSONResponse(status_code=422, content={"detail": safe_errors})

    @application.middleware("http")
    async def request_context(request: Request, call_next):
        """요청 ID와 처리 시간을 남기고 같은 ID를 응답 헤더에 넣는다."""
        supplied_request_id = request.headers.get("X-Request-Id", "")
        request_id = (
            supplied_request_id
            if SAFE_REQUEST_ID_PATTERN.fullmatch(supplied_request_id)
            else str(uuid4())
        )
        started_at = time.perf_counter()

        try:
            response = await call_next(request)
        except Exception as exc:
            logger.error(
                "request_failed request_id=%s method=%s path=%s error_type=%s",
                request_id,
                request.method,
                request.url.path,
                type(exc).__name__,
            )
            raise

        duration_ms = round((time.perf_counter() - started_at) * 1000)
        response.headers["X-Request-Id"] = request_id
        logger.info(
            "request_completed request_id=%s method=%s path=%s status=%s duration_ms=%s",
            request_id,
            request.method,
            request.url.path,
            response.status_code,
            duration_ms,
        )
        return response

    return application


app = create_app()
