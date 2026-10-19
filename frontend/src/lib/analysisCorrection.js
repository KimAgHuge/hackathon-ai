const object = (value) => value !== null && typeof value === 'object' && !Array.isArray(value)

// 문장 입력으로 수정할 수 있는 경로만 허용하고 나머지 정답·근거·점수는 그대로 보존한다.
export const correctionFields = [
    {
        value: 'recommendations',
        label: '권장 대응 방안',
        kind: 'list',
        hint: '실제로 수행할 작업과 확인할 내용을 항목별로 적습니다. 기존 문장에서 잘못된 부분만 수정해도 됩니다.',
    },
    {
        value: 'impactAreas',
        label: '영향 영역',
        kind: 'list',
        hint: '변경할 화면·기능·파일 등 영향을 받는 대상을 적습니다. 확인하지 않은 대상을 확정하지 마세요.',
    },
    { value: 'summary', label: '분석 요약', kind: 'text', hint: '변경 범위와 제외 범위, 판단 근거 및 남은 한계를 정리합니다.', maxLength: 10000 },
    {
        value: 'plainLanguageSummary',
        label: '쉽게 보는 한 줄 요약',
        kind: 'text',
        hint: '비개발자도 이해할 수 있게 한 줄, 200자 이하로 적습니다.',
        maxLength: 200,
    },
]

export const parseAnalysisCorrection = (text) => {
    let result
    try {
        result = JSON.parse(text)
    } catch {
        throw new Error('전체 JSON의 쉼표·따옴표·괄호를 확인해 주세요. 문법을 고치면 항목별 수정을 다시 사용할 수 있습니다.')
    }
    if (!object(result)) throw new Error('수정 결과는 전체 분석 항목이 들어 있는 JSON 객체여야 합니다.')
    return result
}

const selectedField = (key) => {
    const field = correctionFields.find((item) => item.value === key)
    if (!field) throw new Error('항목별 수정에서 지원하지 않는 경로입니다. 위험도·DB 판단·근거는 전체 JSON의 안내를 확인해 주세요.')
    return field
}

export const updateAnalysisCorrection = (text, key, value) => {
    const result = parseAnalysisCorrection(text)
    const field = selectedField(key)
    if (!Object.hasOwn(result, key)) throw new Error('원래 결과에 이 항목이 없습니다. 전체 JSON의 필수 항목 안내를 확인해 주세요.')
    if (field.kind === 'list' ? !Array.isArray(value) || value.some((item) => typeof item !== 'string') : typeof value !== 'string') {
        throw new Error('문장 항목은 글로, 목록 항목은 문장 목록으로 입력해 주세요.')
    }
    return JSON.stringify({ ...result, [key]: value }, null, 2)
}

export const updateAnalysisCorrectionItem = (text, key, index, value) => {
    const result = parseAnalysisCorrection(text)
    const field = selectedField(key)
    const items = result[key]
    if (field.kind !== 'list' || !Array.isArray(items) || !Number.isInteger(index) || index < 0 || index >= items.length || typeof value !== 'string') {
        throw new Error('수정할 목록 항목의 위치와 문장을 확인해 주세요.')
    }
    return updateAnalysisCorrection(
        text,
        key,
        items.map((item, position) => (position === index ? value : item))
    )
}

// 편집 중 빈 값은 허용하되 승인 전에 위치를 안내한다. 전체 서버 검증을 대신하지 않는다.
export const analysisCorrectionIssues = (text) => {
    let result
    try {
        result = parseAnalysisCorrection(text)
    } catch (error) {
        return [error.message]
    }
    const issues = []
    for (const field of correctionFields) {
        const value = result[field.value]
        if (field.kind === 'list') {
            if (!Array.isArray(value)) issues.push(`${field.label}: 문장 목록이어야 합니다.`)
            else
                value.forEach((item, index) => {
                    if (typeof item !== 'string' || !item.trim() || item.length > 10000)
                        issues.push(`${field.label} ${index + 1}: 1자 이상 10,000자 이하의 문장을 입력하거나 항목을 삭제해 주세요.`)
                })
        } else if (typeof value !== 'string' || !value.trim() || value.length > field.maxLength) {
            issues.push(`${field.label}: 1자 이상 ${field.maxLength.toLocaleString('ko-KR')}자 이하로 입력해 주세요.`)
        } else if (field.value === 'plainLanguageSummary' && /[\r\n]/.test(value)) {
            issues.push('쉽게 보는 한 줄 요약: 줄바꿈 없이 한 줄로 입력해 주세요.')
        }
    }
    if (text.length > 200000) issues.push('전체 수정 결과는 200,000자 이하여야 합니다.')
    return issues
}
