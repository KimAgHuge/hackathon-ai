package com.doenaai.analysis.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AnalysisJobResponse(
        UUID jobId,
        UUID projectId,
        UUID requirementVersionId,
        String status,
        int sourceProgress,
        int databaseProgress,
        int relationProgress,
        int overallProgress,
        OffsetDateTime createdAt
) {

    public static AnalysisJobResponse of(
            UUID jobId,
            UUID projectId,
            UUID requirementVersionId,
            String status,
            int sourceProgress,
            int databaseProgress,
            int relationProgress,
            OffsetDateTime createdAt
    ) {
        int overallProgress =
                (
                        sourceProgress +
                        databaseProgress +
                        relationProgress
                ) / 3;

        return new AnalysisJobResponse(
                jobId,
                projectId,
                requirementVersionId,
                status,
                sourceProgress,
                databaseProgress,
                relationProgress,
                overallProgress,
                createdAt
        );
    }
}
