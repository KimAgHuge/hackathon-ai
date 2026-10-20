const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value)
const isEmpty = (value) => value === null || value === undefined || (typeof value === 'string' && !value.trim())
const relevanceValues = ['RELATED', 'UNRELATED', 'UNCERTAIN']
const completionPaths = ['snapshot.databaseCapturedAt', 'answers.general.databaseRelevance', 'answers.general.databaseRelevanceReason']

// 파일 단위 인용과 정확한 줄 인용을 구분한다. 사용자가 적은 확인 문장은 소스 근거로 취급하지 않는다.
export const unmatchedTrainingEvidence = (text) => {
    let draft
    try {
        draft = readTrainingDraft(text)
    } catch {
        return []
    }
    const references = new Set()
    const files = new Set()
    const collect = (value) => {
        if (Array.isArray(value)) value.forEach(collect)
        else if (isObject(value)) {
            const file = value.file ?? value.path
            if (typeof file === 'string' && file.trim()) {
                files.add(file)
                if (Number.isInteger(value.line) && value.line > 0) references.add(`${file}:${value.line}`)
            }
            Object.values(value).forEach(collect)
        }
    }
    collect(Object.fromEntries(Object.entries(draft.request?.sourceSummary || {}).filter(([key]) => key !== 'changeRequirement')))
    collect(draft.request?.relations || [])
    return (Array.isArray(draft.answers?.general?.evidences) ? draft.answers.general.evidences : [])
        .filter((item) =>
            item?.line == null ? !files.has(item?.file) : !Number.isInteger(item.line) || item.line < 1 || !references.has(`${item.file}:${item.line}`)
        )
        .slice(0, 100)
        .map((item) => ({
            file: typeof item?.file === 'string' ? item.file : '파일 정보 없음',
            line: item?.line == null ? null : Number.isInteger(item.line) && item.line > 0 ? item.line : '?',
        }))
}

// 서버가 반환한 정해진 검토 코드만 화면 안내로 바꾸며 원문 메시지는 노출하지 않는다.
export const trainingReviewWarnings = (response) =>
    Array.isArray(response?.reviewWarnings) && response.reviewWarnings.includes('DATABASE_SCOPE_REVIEW')
        ? [
              '요구사항에 DB 관련 표현이 있습니다. 변경을 요구한 내용인지 제외 범위를 설명한 것인지와 DB 관련성 판단이 맞는지 확인해 주세요. 단어만으로 분석 단계를 강제하지 않습니다.',
          ]
        : []

export const trainingValidationMessage = (error) => {
    const code = error?.response?.data?.code
    const messages = {
        RECEIVER_UPDATE_REQUIRED: 'AI 학습 서버의 검증 규칙 업데이트 또는 연결 확인이 필요합니다. 파일은 아직 게시하지 않았습니다.',
        GROUNDING_MISMATCH: '승인 정답의 근거가 학습 입력과 일치하지 않습니다. 원본 커밋 근거 확인·보강 후 다시 검토해 주세요.',
        CASE_RESPONSE_INVALID: '단계별 정답의 형식과 DB 변경안의 중복·필수 여부를 확인해 주세요.',
        CASE_NOT_SANITIZED: '비밀정보 또는 비식별화가 필요한 문자열이 있습니다. 원문을 검토해 주세요.',
        CASE_CONTRACT_INVALID: '학습 사례의 필수 항목과 승인·스냅샷 정보를 확인해 주세요.',
        EVIDENCE_SOURCE_UNAVAILABLE: '분석 당시 Git 커밋을 확인하지 못했습니다. 저장소 연결과 커밋을 확인해 주세요.',
        EVIDENCE_INVALID_REFERENCE: '학습 사례의 분석 연결 정보나 근거 파일·줄 번호가 올바르지 않습니다.',
        EVIDENCE_LIMIT: '보완할 근거가 너무 많습니다. 정답 근거를 검토하거나 새 분석을 사용해 주세요.',
    }
    if (Object.hasOwn(messages, code)) return `${messages[code]} (오류 코드: ${code})`
    if (error?.response?.status === 422)
        return '학습 사례 검증에서 거부됐습니다. 백엔드와 AI 분석/추론 서버가 최신 코드인지 확인하고 전체 검사를 실행해 주세요.'
    return error?.response?.data?.message || error?.message || '학습 사례 검사 또는 근거 보강을 완료하지 못했습니다.'
}

// 편집 중인 초안만 해석하며 JSON 원문을 저장소나 로그에 기록하지 않는다.
export const readTrainingDraft = (text) => {
    let draft
    try {
        draft = JSON.parse(text)
    } catch {
        throw new Error('학습 사례 JSON 문법을 확인해 주세요.')
    }
    if (!isObject(draft)) throw new Error('학습 사례는 JSON 객체여야 합니다.')
    return draft
}

export const trainingDraftValue = (draft, path) => path.split('.').reduce((value, key) => (isObject(value) ? value[key] : undefined), draft)

// 지정한 보완 항목만 바꾸고 확인 표시는 다시 선택하도록 해제한다.
export const updateTrainingDraftField = (text, path, value) => {
    if (!completionPaths.includes(path)) throw new Error('허용된 보완 항목만 수정할 수 있습니다.')
    const draft = readTrainingDraft(text)
    const keys = path.split('.')
    let target = draft
    for (const key of keys.slice(0, -1)) {
        if (target[key] === undefined || target[key] === null) target[key] = {}
        if (!isObject(target[key])) throw new Error('초안의 스냅샷과 단계별 정답은 JSON 객체여야 합니다.')
        target = target[key]
    }
    target[keys.at(-1)] = value
    return { ...draft, humanApproved: false, sanitized: false }
}

// 서버가 새로 복원한 값 중 빈 항목만 합치고 사용자의 수정값은 덮어쓰지 않는다.
export const completeMissingTrainingFields = (text, savedDraft) => {
    let draft = readTrainingDraft(text)
    if (!isObject(savedDraft) || !draft.caseId || draft.caseId !== savedDraft.caseId) throw new Error('같은 분석 작업의 초안만 보완할 수 있습니다.')
    const filledPaths = []
    for (const path of completionPaths) {
        const next = trainingDraftValue(savedDraft, path)
        if (isEmpty(trainingDraftValue(draft, path)) && !isEmpty(next)) {
            draft = updateTrainingDraftField(JSON.stringify(draft), path, next)
            filledPaths.push(path)
        }
    }
    return { caseDraft: draft, filledPaths }
}

const isTimestamp = (value) => {
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/.test(value)) return false
    const date = value.slice(0, 10)
    return !Number.isNaN(Date.parse(value)) && new Date(`${date}T00:00:00Z`).toISOString().slice(0, 10) === date
}

// 누락값과 분석 분기 불일치를 전송 전에 안내한다. 최종 계약 검사는 서버가 수행한다.
export const trainingDraftIssues = (text) => {
    let draft
    try {
        draft = readTrainingDraft(text)
    } catch (error) {
        return [error.message]
    }
    const issues = []
    if (!isTimestamp(draft.snapshot?.databaseCapturedAt))
        issues.push('DB 수집 시각: 당시 기록에 근거한 시간대 포함 시각이 필요합니다. 복원할 기록이 없다면 새 분석을 사용해 주세요.')
    if (!/^[0-9a-f]{40}$/.test(draft.snapshot?.sourceRevision || '') || /^0{40}$/.test(draft.snapshot?.sourceRevision || '')) {
        issues.push('소스 커밋: 실제 분석에 사용한 40자리 Git 커밋이 필요합니다.')
    }
    if (isEmpty(draft.snapshot?.requirementVersionId)) issues.push('요구사항 버전이 필요합니다.')
    if (!isTimestamp(draft.approval?.approvedAt)) issues.push('승인 시각이 올바르지 않습니다.')
    if (typeof draft.approval?.note !== 'string' || !draft.approval.note.trim()) issues.push('검토 이유가 필요합니다.')
    const general = draft.answers?.general
    if (!relevanceValues.includes(general?.databaseRelevance)) issues.push('DB 관련성을 선택해 주세요.')
    if (typeof general?.databaseRelevanceReason !== 'string' || !general.databaseRelevanceReason.trim()) issues.push('DB 관련성의 판단 이유를 작성해 주세요.')
    if (general?.databaseRelevance === 'UNRELATED' && draft.answers?.database)
        issues.push('DB 관련 없음 판단과 DB 단계 정답이 함께 있습니다. 정답을 검토해 분석 분기를 맞춰 주세요.')
    if (['RELATED', 'UNCERTAIN'].includes(general?.databaseRelevance) && !isObject(draft.answers?.database))
        issues.push('DB 관련 있음 또는 판단 유보에는 검토한 DB 단계 정답이 필요합니다.')
    return issues
}

// 편집으로 해결한 누락 안내만 숨기고 근거·정답 검토 안내는 계속 표시한다.
export const remainingTrainingReviewNotes = (notes, text) => {
    let draft
    try {
        draft = readTrainingDraft(text)
    } catch {
        return notes
    }
    return notes.filter((note) => {
        const path = note.split(':')[0]
        if (path === 'snapshot.databaseCapturedAt') return !isTimestamp(trainingDraftValue(draft, path))
        if (path === 'answers.general.databaseRelevance') return !relevanceValues.includes(trainingDraftValue(draft, path))
        if (path === 'answers.general.databaseRelevanceReason') return isEmpty(trainingDraftValue(draft, path))
        if (path === 'humanApproved, sanitized') return draft.humanApproved !== true || draft.sanitized !== true
        return true
    })
}
