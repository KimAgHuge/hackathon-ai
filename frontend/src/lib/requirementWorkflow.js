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

/** 분석마다 추론 설정을 명시하고, 선택한 저장 근거와 요구사항의 근거 선택 방식을 함께 보낸다. */
export const buildAnalysisJobPayload = (
    requirementVersionId,
    reuseFromJobId = null,
    thinkingEnabled = true,
    thinkingEffort = 'medium',
    evidenceSelection = 'AUTO'
) => {
    if (!['low', 'medium', 'high'].includes(thinkingEffort)) {
        throw new Error('추론 강도는 낮음, 보통, 높음 중에서 선택해 주세요.')
    }
    return {
        thinkingEnabled: thinkingEnabled !== false,
        thinkingEffort,
        ...(requirementVersionId ? { requirementVersionId } : {}),
        ...(reuseFromJobId ? { reuseFromJobId, confirmedUnchanged: true } : {}),
        // 자동 선택은 서버 기본값을 사용하며 요구사항 분석에만 선택값을 보낸다.
        ...(requirementVersionId && ['ALWAYS', 'OFF'].includes(evidenceSelection) ? { evidenceSelection } : {}),
    }
}

/** AI 모델에 보낼 근거를 고르는 방식이다. 요구사항 영향 분석에서만 사용한다. */
export const EVIDENCE_SELECTION_OPTIONS = [
    { value: 'AUTO', label: '자동 (근거가 많을 때만 관련 근거 선택)' },
    { value: 'ALWAYS', label: '항상 관련 근거만 골라 보내기 (RAG)' },
    { value: 'OFF', label: '선택 없이 전체 근거 보내기' },
]
