"""Backend만 호출할 수 있는 승인 학습 사례 전송 API다."""

from uuid import UUID
import logging

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict

from app.config import Settings, get_settings
from app.dependencies import require_internal_api_key
from app.services.training_transfer_service import (
    DatasetError, TrainingTransferService, TransferConfigurationError, TransferError, validate_case, review_flags, ReceiverCompatibilityError,
)


router = APIRouter(prefix="/api/training/transfers", tags=["training"],
                   dependencies=[Depends(require_internal_api_key)])
logger = logging.getLogger("doenaai.training")


def case_error(error, job_id):
    """원문 대신 고정 오류 코드와 검사 단계만 응답·로그에 남긴다."""
    messages = {
        "GROUNDING_MISMATCH": "승인 정답의 근거가 학습 입력과 일치하지 않습니다. 원본 커밋의 근거 보강 후 다시 검토해 주세요.",
        "CASE_RESPONSE_INVALID": "단계별 정답의 형식과 DB 변경안의 중복·필수 여부를 확인해 주세요.",
        "CASE_NOT_SANITIZED": "비밀정보 또는 비식별화가 필요한 문자열이 있습니다. 원문을 검토해 주세요.",
        "CASE_CONTRACT_INVALID": "학습 사례의 필수 항목과 승인·스냅샷 정보를 확인해 주세요.",
    }
    code = error.code if error.code in messages else "CASE_CONTRACT_INVALID"
    field = error.field if error.field in {"caseDraft", "answers.general", "answers.database"} else "caseDraft"
    logger.warning("training_case_validation_failed job_id=%s code=%s field=%s", job_id, code, field)
    return HTTPException(status_code=422, detail={"code": code, "field": field, "message": messages[code]})


class TransferRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    job_id: UUID
    case_draft: dict


@router.post("/validate")
def validate_training_case(request: TransferRequest):
    """파일 게시·알림·학습 없이 전체 사례 계약만 검사한다."""
    try:
        if request.case_draft.get("caseId") != f"analysis-{request.job_id}":
            raise DatasetError("분석 작업과 사례가 일치하지 않습니다.")
        validate_case(request.case_draft)
        flags = review_flags("general", request.case_draft["answers"]["general"], request.case_draft["request"])
        if "database_scope_requires_review" in flags:
            return {"valid": True, "reviewWarnings": ["DATABASE_SCOPE_REVIEW"]}
        return {"valid": True}
    except DatasetError as error:
        raise case_error(error, request.job_id) from None


@router.post("")
def publish_training_case(request: TransferRequest, settings: Settings = Depends(get_settings)):
    """원문을 응답·로그에 포함하지 않고 전송 상태만 반환한다."""
    try:
        return TrainingTransferService(settings).publish(request.job_id, request.case_draft)
    except ReceiverCompatibilityError:
        raise HTTPException(status_code=503, detail={"code": "RECEIVER_UPDATE_REQUIRED",
                                                      "message": "AI 학습 서버의 검증 규칙 업데이트 또는 연결 확인이 필요합니다. 파일은 아직 게시하지 않았습니다."}) from None
    except TransferConfigurationError:
        raise HTTPException(status_code=503, detail={"code": "TRANSFER_NOT_CONFIGURED",
                                                      "message": "공유 폴더·AI 학습 서버 주소·전송 키를 먼저 설정하세요."}) from None
    except DatasetError as error:
        raise case_error(error, request.job_id) from None
    except (TransferError, ValueError):
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
                                                      "message": "공유 폴더·AI 학습 서버 주소·전송 키를 먼저 설정하세요."}) from None
    except (TransferError, OSError, ValueError):
        raise HTTPException(status_code=503, detail={"code": "TRANSFER_UNAVAILABLE",
                                                      "message": "학습 전송 설정이나 공유 폴더를 확인하세요."}) from None
    if not status:
        raise HTTPException(status_code=404, detail={"code": "TRANSFER_NOT_FOUND"})
    return status
