package com.doenaai.analysis.dto;

import java.util.UUID;

/**
 * @param evidenceSelection 요구사항 분석에서 AI 모델에 보낼 근거 방식.
 *                          AUTO(기본, 비어 있어도 같음) · ALWAYS(관련 근거만 골라 전달) · OFF(전체 근거 전달)
 */
public record AnalysisJobCreateRequest(
        UUID requirementVersionId,
        UUID reuseFromJobId,
        Boolean confirmedUnchanged,
        String evidenceSelection
) {
    public AnalysisJobCreateRequest(UUID requirementVersionId, UUID reuseFromJobId, Boolean confirmedUnchanged) {
        this(requirementVersionId, reuseFromJobId, confirmedUnchanged, null);
    }
}
