// 확인란의 선택을 학습 사례의 승인·비식별화 값에 반영한다.
export const applyTrainingReviewConfirmations = (text, answerChecked, sanitizedChecked) => {
    let caseDraft
    try {
        caseDraft = JSON.parse(text)
    } catch {
        throw new Error('학습 사례 JSON 문법을 확인해 주세요.')
    }
    if (!caseDraft || typeof caseDraft !== 'object' || Array.isArray(caseDraft)) {
        throw new Error('학습 사례는 JSON 객체여야 합니다.')
    }
    return { ...caseDraft, humanApproved: answerChecked === true, sanitized: sanitizedChecked === true }
}
