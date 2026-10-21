"""내부 인증 뒤 설치 모델 시험·전환·직전 모델 복구만 허용한다."""

from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Response
from pydantic import ConfigDict, Field, StrictInt

from app.dependencies import get_model_runtime_service, require_internal_api_key
from app.models.base import ApiModel, to_camel
from app.services.model_runtime import ModelRuntimeError, ModelRuntimeService

router = APIRouter(prefix="/api/models", tags=["model-runtime"], dependencies=[Depends(require_internal_api_key)])


class RuntimeInput(ApiModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")


class ProbeInput(RuntimeInput):
    model: str = Field(min_length=1, max_length=200)
    digest: str = Field(min_length=64, max_length=71)


class ActivateInput(ProbeInput):
    operation_id: UUID
    probe_token: str = Field(min_length=20, max_length=200)
    expected_revision: StrictInt = Field(ge=0)


class RollbackInput(RuntimeInput):
    operation_id: UUID
    expected_revision: StrictInt = Field(ge=0)


class AdapterImportInput(RuntimeInput):
    job_id: UUID
    version: str = Field(pattern=r"^web-[0-9a-f-]{36}$")
    manifest_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    source_model: str = Field(min_length=1, max_length=200)
    base_model: str = Field(min_length=1, max_length=200)
    base_revision: str = Field(pattern=r"^[0-9a-f]{40}$")


@router.post("/adapters/import")
async def import_adapter(body: AdapterImportInput, response: Response, service: ModelRuntimeService = Depends(get_model_runtime_service)):
    """서명된 공유 패키지를 로컬에 준비하며 추론과 모델 전환은 실행하지 않는다."""
    from app.services.mlx_adapter_runtime import MlxAdapterError
    response.headers["Cache-Control"] = "no-store"
    try:
        return await service.adapters.prepare(body.model_dump(mode="json", by_alias=True))
    except MlxAdapterError as error:
        raise HTTPException(error.status, detail={"code": error.code, "message": error.message}) from None


def unavailable(error):
    return HTTPException(status_code=error.status, detail={"code": error.code, "message": error.message})


@router.get("/installed")
async def installed(response: Response, service: ModelRuntimeService = Depends(get_model_runtime_service)):
    """파일을 받거나 모델을 실행하지 않고 설치 모델과 선택 상태를 조회한다."""
    response.headers["Cache-Control"] = "no-store"
    try:
        return await service.installed()
    except ModelRuntimeError as error:
        raise unavailable(error) from None


@router.post("/probe")
async def probe(body: ProbeInput, response: Response, service: ModelRuntimeService = Depends(get_model_runtime_service)):
    """명시적으로 요청한 설치 모델에 짧은 고정 시험 질문을 보낸다."""
    response.headers["Cache-Control"] = "no-store"
    try:
        return await service.probe(body.model, body.digest)
    except ModelRuntimeError as error:
        raise unavailable(error) from None


@router.post("/activate")
async def activate(body: ActivateInput, response: Response, service: ModelRuntimeService = Depends(get_model_runtime_service)):
    """유효한 시험 증거와 같은 선택 버전일 때 새 요청의 모델만 변경한다."""
    response.headers["Cache-Control"] = "no-store"
    try:
        return await service.activate(body.operation_id, body.model, body.digest, body.probe_token, body.expected_revision)
    except ModelRuntimeError as error:
        raise unavailable(error) from None


@router.post("/rollback")
async def rollback(body: RollbackInput, response: Response, service: ModelRuntimeService = Depends(get_model_runtime_service)):
    """직전 모델의 동일 파일이 설치돼 있을 때만 그 선택값으로 한 번 복구한다."""
    response.headers["Cache-Control"] = "no-store"
    try:
        return await service.rollback(body.operation_id, body.expected_revision)
    except ModelRuntimeError as error:
        raise unavailable(error) from None


@router.get("/operations/{operation_id}")
def operation(operation_id: UUID, response: Response, service: ModelRuntimeService = Depends(get_model_runtime_service)):
    """응답 유실이나 재시작 뒤에도 동일 식별자의 처리 결과를 확인한다."""
    response.headers["Cache-Control"] = "no-store"
    try:
        return service.operation(operation_id)
    except ModelRuntimeError as error:
        raise unavailable(error) from None
