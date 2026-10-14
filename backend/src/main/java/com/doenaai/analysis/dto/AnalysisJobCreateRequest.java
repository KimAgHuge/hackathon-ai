package com.doenaai.analysis.dto;

import java.util.UUID;

public record AnalysisJobCreateRequest(
        UUID requirementVersionId,
        UUID reuseFromJobId,
        Boolean confirmedUnchanged
) {
}
