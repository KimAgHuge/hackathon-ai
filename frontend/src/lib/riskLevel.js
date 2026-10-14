const labels = {
    LOW: '낮음',
    MEDIUM: '보통',
    HIGH: '높음',
    CRITICAL: '매우 높음',
    UNKNOWN: '판단 불가',
}

/** API의 위험도 코드를 화면 표시용 한글로 변환한다. */
export const riskLevelLabel = (level) => {
    if (!level) return '미산정'
    return labels[String(level).toUpperCase()] || '확인 필요'
}
