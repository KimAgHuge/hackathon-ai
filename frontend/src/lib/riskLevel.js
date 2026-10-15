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

const factorLimits = {
    changeScope: 25,
    dataImpact: 20,
    securityImpact: 25,
    deliveryComplexity: 20,
    uncertainty: 10,
}

/** 같은 점수 구간을 새 분석과 저장된 결과 화면에 적용한다. */
export const riskLevelForScore = (score) => {
    if (!Number.isInteger(score) || score < 0 || score > 100) return null
    if (score >= 75) return 'CRITICAL'
    if (score >= 50) return 'HIGH'
    if (score >= 25) return 'MEDIUM'
    return 'LOW'
}

/** 오래된 결과의 모순을 감추지 않고 표시할 수 있도록 점수와 근거를 점검한다. */
export const riskPresentation = (level, score, assessment) => {
    if (level === 'UNKNOWN') return { label: '판단 불가', score: null, warning: '', provisional: true }
    if (riskLevelForScore(score) !== level) {
        return {
            label: '점검 필요',
            score: null,
            warning: '저장된 위험도 등급과 점수가 일치하지 않습니다. 이 결과를 승인하기 전에 다시 분석해 주세요.',
            provisional: false,
        }
    }
    if (assessment) {
        const names = Object.keys(factorLimits)
        const valid =
            Object.keys(assessment).length === names.length &&
            names.every((name) => {
                const factor = assessment[name]
                return (
                    Number.isInteger(factor?.score) &&
                    factor.score >= 0 &&
                    factor.score <= factorLimits[name] &&
                    typeof factor.reason === 'string' &&
                    factor.reason.trim()
                )
            })
        if (!valid || names.reduce((sum, name) => sum + assessment[name].score, 0) !== score) {
            return {
                label: '점검 필요',
                score: null,
                warning: '저장된 위험도 점수와 평가 근거가 일치하지 않습니다. 이 결과를 승인하기 전에 다시 분석해 주세요.',
                provisional: false,
            }
        }
    }
    return { label: riskLevelLabel(level), score, warning: '', provisional: (assessment?.uncertainty?.score ?? 0) >= 5 }
}
