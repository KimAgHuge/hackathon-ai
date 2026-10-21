export const activeTrainingStatuses = new Set(['PENDING', 'PREPARING', 'TRAINING', 'VERIFYING', 'RUNNING', 'CLAIMED', 'UNKNOWN'])
export const uncertainOperationStatuses = new Set(['UNKNOWN', 'PENDING', 'RUNNING'])
export const operationStatusLabel = (status) =>
    ({
        PENDING: '접수 대기',
        PREPARING: '자료 준비 중',
        TRAINING: '학습 중',
        VERIFYING: '검증 중',
        SUCCEEDED: '완료',
        FAILED: '실패',
        CANCELLED: '취소됨',
        CANCEL_REQUESTED: '취소 요청됨',
        PASSED: '검증 통과',
        APPLIED: '전환 적용',
        ROLLED_BACK: '이전 모델 복구',
        UNKNOWN: '결과 확인 필요',
        RUNNING: '진행 중',
        COMPLETED: '완료',
    })[status] ||
    status ||
    '미확인'
export const operationActionLabel = (action) =>
    ({
        PROBE: '설치 모델 검증',
        MODEL_PROBE: '설치 모델 검증',
        ACTIVATE: '모델 전환',
        MODEL_ACTIVATE: '모델 전환',
        ROLLBACK: '이전 모델 복구',
        MODEL_ROLLBACK: '이전 모델 복구',
        TRAINING_START: '학습 시작',
        TRAINING_CANCEL: '학습 취소',
        TRAIN: '학습 시작',
        CANCEL_TRAINING: '학습 취소',
    })[action] ||
    action ||
    '기록 없음'
export const operationErrorMessage = (error, fallback) => {
    if (error?.response?.status === 401) return '로그인이 만료되었습니다. 다시 로그인해 주세요.'
    if (error?.response?.status === 403) return '이 작업에 필요한 권한이 없습니다.'
    if (error?.response?.status === 409) return '다른 작업이나 모델 변경이 감지되었습니다. 최신 상태와 작업 이력을 확인해 주세요.'
    return typeof error?.response?.data?.message === 'string' ? error.response.data.message : fallback
}
export const uncertainRequestError = (error) => !error?.response || error.response.status >= 500 || error.response.status === 408
export const formatOperationDate = (value) => {
    if (!value) return '기록 없음'
    const date = new Date(value)
    return Number.isNaN(date.getTime()) ? '시각 확인 불가' : date.toLocaleString('ko-KR')
}
export const probeMatchesSelection = (probe, model, snapshot, now = Date.now()) =>
    Boolean(
        probe?.status === 'PASSED' &&
        typeof probe.probeToken === 'string' &&
        probe.probeToken.trim() &&
        model?.selectable === true &&
        typeof model.name === 'string' &&
        model.name.trim() &&
        typeof model.digest === 'string' &&
        model.digest.trim() &&
        Number.isSafeInteger(snapshot?.currentRevision) &&
        snapshot.currentRevision >= 0 &&
        Number.isSafeInteger(probe.currentRevision) &&
        probe.currentRevision >= 0 &&
        probe.model === model.name &&
        probe.digest === model.digest &&
        probe.currentRevision === snapshot?.currentRevision &&
        Number.isFinite(now) &&
        typeof probe.expiresAt === 'string' &&
        new Date(probe.expiresAt).getTime() > now
    )

// 활성 작업에만 순차 조회를 예약하고 숨김·오류·종료 뒤에는 자동 요청을 멈춘다.
export const createTrainingPoller = ({ fetchStatus, applyStatus, onError, isHidden, setTimer = setTimeout, clearTimer = clearTimeout }) => {
    let timer = null
    let generation = 0
    let stopped = false
    const clear = () => {
        if (timer !== null) clearTimer(timer)
        timer = null
    }
    const refresh = async () => {
        if (stopped || isHidden()) return
        clear()
        const current = ++generation
        try {
            const value = await fetchStatus()
            if (stopped || current !== generation || isHidden()) return
            applyStatus(value)
            if ((value.jobs || []).some((job) => activeTrainingStatuses.has(job.status))) timer = setTimer(refresh, 5000)
        } catch (error) {
            if (!stopped && current === generation) onError(error)
        }
    }
    return {
        refresh,
        pause() {
            clear()
            generation += 1
        },
        stop() {
            stopped = true
            clear()
            generation += 1
        },
    }
}
