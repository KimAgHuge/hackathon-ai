/** 화면의 빈 입력을 제외하고 원본 분석의 질문 순번을 보존한다. */
export const buildFollowUpPayload = (sourceAnalysisJobId, expectedVersionId, answerInputs, followUpQuestion) => {
    const answers = answerInputs.map((answer, questionIndex) => ({ questionIndex, answer: String(answer || '').trim() })).filter((item) => item.answer)
    const question = String(followUpQuestion || '').trim()
    if (!answers.length && !question) throw new Error('확인 질문의 답변이나 추가 질문을 입력해 주세요.')
    return {
        sourceAnalysisJobId,
        expectedVersionId,
        answers,
        followUpQuestion: question || null,
    }
}

/** 취소·실패한 후속 분석 버전이라면 원본 분석의 질문에서 다시 이어갈 수 있다. */
export const canContinueFromAnalysis = (sourceAnalysisJobId, expectedVersionId, currentVersion) =>
    Boolean(currentVersion && (currentVersion.id === expectedVersionId || currentVersion.sourceAnalysisJobId === sourceAnalysisJobId))

/** 취소·실패한 후속 분석에 저장된 답변을 원래 질문 순서로 복원한다. */
export const savedFollowUpDraft = (sourceAnalysisJobId, clarifications) => {
    const answers = []
    let question = ''
    for (const item of Array.isArray(clarifications) ? clarifications : []) {
        if (item?.sourceAnalysisJobId !== sourceAnalysisJobId) continue
        if (item.kind === 'AI_QUESTION_ANSWER' && Number.isInteger(item.questionIndex) && item.questionIndex >= 0) {
            answers[item.questionIndex] = item.answer || ''
        } else if (item.kind === 'USER_QUESTION') {
            question = item.question || ''
        }
    }
    return { answers, question }
}
