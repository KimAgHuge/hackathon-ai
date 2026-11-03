package com.doenaai.analysis.dto;

import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/**
 * @param confirmedUnchanged 기존 연동을 위한 필드명. 선택한 저장 시점으로 분석한다는 확인이며 최신 상태와 같다는 뜻은 아니다.
 * @param evidenceSelection 요구사항 분석에서 AI 모델에 보낼 근거 방식.
 *                          AUTO(기본, 비어 있어도 같음) · ALWAYS(관련 근거만 골라 전달) · OFF(전체 근거 전달)
 */
public record AnalysisJobCreateRequest(
        UUID requirementVersionId,
        UUID reuseFromJobId,
        Boolean confirmedUnchanged,
        Boolean thinkingEnabled,
        @Pattern(regexp = "low|medium|high", message = "추론 강도는 low, medium, high 중에서 선택해야 합니다.")
        String thinkingEffort,
        String evidenceSelection
) {
    public AnalysisJobCreateRequest {
        if (thinkingEnabled == null) thinkingEnabled = true;
        if (thinkingEffort == null) thinkingEffort = "medium";
    }

    public AnalysisJobCreateRequest(UUID requirementVersionId, UUID reuseFromJobId, Boolean confirmedUnchanged,
                                    Boolean thinkingEnabled, String thinkingEffort) {
        this(requirementVersionId, reuseFromJobId, confirmedUnchanged, thinkingEnabled, thinkingEffort, null);
    }

    public AnalysisJobCreateRequest(UUID requirementVersionId, UUID reuseFromJobId, Boolean confirmedUnchanged,
                                    Boolean thinkingEnabled) {
        this(requirementVersionId, reuseFromJobId, confirmedUnchanged, thinkingEnabled, "medium", null);
    }

    public AnalysisJobCreateRequest(UUID requirementVersionId, UUID reuseFromJobId, Boolean confirmedUnchanged) {
        this(requirementVersionId, reuseFromJobId, confirmedUnchanged, true, "medium", null);
    }
}
