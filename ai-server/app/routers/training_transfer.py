"""Backend만 호출할 수 있는 승인 학습 사례 전송 API다."""

from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict

from app.config import Settings, get_settings
from app.dependencies import require_internal_api_key
from app.services.training_transfer_service import (
    DatasetError, TrainingTransferService, TransferConfigurationError, TransferError,
)


router = APIRouter(prefix="/api/training/transfers", tags=["training"],
                   dependencies=[Depends(require_internal_api_key)])


class TransferRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    job_id: UUID
    case_draft: dict


@router.post("")
def publish_training_case(request: TransferRequest, settings: Settings = Depends(get_settings)):
    """원문을 응답·로그에 포함하지 않고 전송 상태만 반환한다."""
    try:
        return TrainingTransferService(settings).publish(request.job_id, request.case_draft)
    except TransferConfigurationError:
        raise HTTPException(status_code=503, detail={"code": "TRANSFER_NOT_CONFIGURED",
                                                      "message": "공유 폴더·맥북 수신 주소·전송 키를 먼저 설정하세요."}) from None
    except (DatasetError, TransferError, ValueError):
        raise HTTPException(status_code=422, detail={"code": "INVALID_TRAINING_CASE",
                                                      "message": "학습 사례 계약·비식별 확인 또는 전송 설정을 확인하세요."}) from None
    except OSError:
        raise HTTPException(status_code=503, detail={"code": "TRANSFER_STORAGE_UNAVAILABLE",
                                                      "message": "공유 폴더에 접근하지 못했습니다."}) from None


@router.get("/by-job/{job_id}")
def training_transfer_status(job_id: UUID, settings: Settings = Depends(get_settings)):
    """중단 뒤에도 저장된 전송을 다시 확인하고 수신 알림을 재시도한다."""
    try:
        status = TrainingTransferService(settings).by_job(job_id)
    except TransferConfigurationError:
        raise HTTPException(status_code=503, detail={"code": "TRANSFER_NOT_CONFIGURED",
                                                      "message": "공유 폴더·맥북 수신 주소·전송 키를 먼저 설정하세요."}) from None
    except (TransferError, OSError, ValueError):
        raise HTTPException(status_code=503, detail={"code": "TRANSFER_UNAVAILABLE",
                                                      "message": "학습 전송 설정이나 공유 폴더를 확인하세요."}) from None
    if not status:
        raise HTTPException(status_code=404, detail={"code": "TRANSFER_NOT_FOUND"})
    return status
