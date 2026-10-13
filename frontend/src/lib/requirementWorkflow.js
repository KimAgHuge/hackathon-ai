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

export const buildAnalysisJobPayload = (requirementVersionId) => (requirementVersionId ? { requirementVersionId } : undefined)
