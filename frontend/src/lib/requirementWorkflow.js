/**
 * @param {object} form 변경요청 입력 폼 상태
 * @returns {object} 공백을 정리하고 인수 조건을 줄별 배열로 바꾼 생성 API 본문
 */
export const buildRequirementPayload = (form) => {
    const title = form.title.trim()
    const originalContent = form.originalContent.trim()
    const refinedContent = form.refinedContent.trim()

    if (!title || !originalContent || !refinedContent) {
        throw new Error('제목, 요청 원문, 정리한 변경 내용을 모두 입력해주세요.')
    }

    return {
        requirementKey: form.requirementKey.trim() || null,
        title,
        sourceType: null,
        sourceRef: null,
        priority: form.priority,
        customerVisible: false,
        originalContent,
        refinedContent,
        acceptanceCriteria: form.acceptanceCriteriaText
            .split('\n')
            .map((line) => line.trim())
            .filter(Boolean),
        attachmentsMetadata: [],
        changeNote: null,
    }
}

/** 기본 분석은 최신 Git/DB를 확인하고, 저장 근거를 선택한 경우에만 해당 작업 ID를 보낸다. */
export const buildAnalysisJobPayload = (requirementVersionId, reuseFromJobId = null) => (
    requirementVersionId || reuseFromJobId
        ? { ...(requirementVersionId ? { requirementVersionId } : {}),
            ...(reuseFromJobId ? { reuseFromJobId, confirmedUnchanged: true } : {}) }
        : undefined
)
