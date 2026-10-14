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
