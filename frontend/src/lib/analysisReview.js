// 검토 사유와 선택적 전체 수정 JSON을 서버 요청 형식으로 변환한다.
export const buildAnalysisReviewPayload = (decision, note, useCorrection = false, correctedText = '') => {
    if (!['APPROVED', 'REJECTED'].includes(decision)) throw new Error('검토 결정을 선택해 주세요.')
    const trimmedNote = note.trim()
    if (!trimmedNote || trimmedNote.length > 4000) throw new Error('검토 이유를 1자 이상 4000자 이하로 입력해 주세요.')

    if (decision === 'REJECTED' || !useCorrection) return { decision, note: trimmedNote, correctedResult: null }

    let correctedResult
    try {
        correctedResult = JSON.parse(correctedText)
    } catch {
        throw new Error('수정 결과가 올바른 JSON 형식이 아닙니다.')
    }
    if (!correctedResult || typeof correctedResult !== 'object' || Array.isArray(correctedResult)) {
        throw new Error('수정 결과는 JSON 객체여야 합니다.')
    }
    return { decision, note: trimmedNote, correctedResult }
}
