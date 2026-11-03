/** 과거에 저장하지 않은 설정을 켜짐으로 추정하지 않는다. */
export const analysisThinkingLabel = (thinkingEnabled) => {
    if (thinkingEnabled === true) return '켜짐'
    if (thinkingEnabled === false) return '꺼짐'
    return '모델 기본값'
}

/** 추론을 끈 작업과 강도를 기록하지 않은 과거 작업을 구분한다. */
export const analysisThinkingEffortLabel = (thinkingEffort, thinkingEnabled) => {
    if (thinkingEnabled === false) return '—'
    if (thinkingEffort == null) return '모델 기본값'
    return { low: '낮음', medium: '보통', high: '높음' }[thinkingEffort] || '알 수 없음'
}

/** 서버가 추론 미지원 오류를 반환하면 사용자가 선택을 바꿀 수 있도록 안내한다. */
export const analysisThinkingError = (error) => {
    const values = [
        error?.response?.data?.code,
        error?.response?.data?.message,
        error?.response?.data?.detail?.code,
        error?.response?.data?.detail?.message,
        error?.response?.data?.detail,
        error?.message,
        error,
    ]
    return values.some((value) => typeof value === 'string' && value.includes('THINKING_NOT_SUPPORTED'))
        ? '선택한 모델은 추론 모드 또는 선택한 강도를 지원하지 않습니다. 강도를 바꾸거나 추론 모드를 끄거나 지원하는 모델을 선택해 주세요.'
        : null
}
