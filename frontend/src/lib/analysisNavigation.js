const TERMINAL_STATUSES = new Set(['COMPLETED', 'FAILED', 'CANCELLED'])

/** 상태 확인 전이거나 분석이 진행 중이면 이탈 시 서버 작업 지속을 알려야 한다. */
export const shouldConfirmAnalysisExit = (status) => !TERMINAL_STATUSES.has(status)

/** 브라우저가 허용하는 경우에만 기본 새로고침·탭 종료 확인창을 요청한다. */
export const warnOnAnalysisUnload = (event, status) => {
    if (!shouldConfirmAnalysisExit(status)) return
    event.preventDefault()
    event.returnValue = true
}
