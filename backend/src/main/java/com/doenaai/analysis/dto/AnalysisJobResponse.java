package com.doenaai.analysis.dto;

import java.time.OffsetDateTime;
import java.util.List;
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
        OffsetDateTime createdAt,
        String errorMessage,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        List<AnalysisActivityEvent> activityLog,
        Boolean thinkingEnabled,
        String thinkingEffort,
        AnalysisRequirementContext requirementContext
) {

    public AnalysisJobResponse(UUID jobId, UUID projectId, UUID requirementVersionId, String status,
                               int sourceProgress, int databaseProgress, int relationProgress,
                               int overallProgress, OffsetDateTime createdAt, String errorMessage,
                               OffsetDateTime startedAt, OffsetDateTime completedAt,
                               List<AnalysisActivityEvent> activityLog, Boolean thinkingEnabled, String thinkingEffort) {
        this(jobId, projectId, requirementVersionId, status, sourceProgress, databaseProgress,
                relationProgress, overallProgress, createdAt, errorMessage, startedAt, completedAt,
                activityLog, thinkingEnabled, thinkingEffort, null);
    }

    public AnalysisJobResponse withRequirementContext(AnalysisRequirementContext context) {
        return new AnalysisJobResponse(jobId, projectId, requirementVersionId, status,
                sourceProgress, databaseProgress, relationProgress, overallProgress, createdAt, errorMessage,
                startedAt, completedAt, activityLog, thinkingEnabled, thinkingEffort, context);
    }

    public AnalysisJobResponse(UUID jobId, UUID projectId, UUID requirementVersionId, String status,
                               int sourceProgress, int databaseProgress, int relationProgress,
                               int overallProgress, OffsetDateTime createdAt, String errorMessage,
                               OffsetDateTime startedAt, OffsetDateTime completedAt,
                               List<AnalysisActivityEvent> activityLog, Boolean thinkingEnabled) {
        this(jobId, projectId, requirementVersionId, status, sourceProgress, databaseProgress,
                relationProgress, overallProgress, createdAt, errorMessage, startedAt, completedAt,
                activityLog, thinkingEnabled, null);
    }

    public AnalysisJobResponse(UUID jobId, UUID projectId, UUID requirementVersionId, String status,
                               int sourceProgress, int databaseProgress, int relationProgress,
                               int overallProgress, OffsetDateTime createdAt, String errorMessage,
                               OffsetDateTime startedAt, OffsetDateTime completedAt,
                               List<AnalysisActivityEvent> activityLog) {
        this(jobId, projectId, requirementVersionId, status, sourceProgress, databaseProgress,
                relationProgress, overallProgress, createdAt, errorMessage, startedAt, completedAt,
                activityLog, null, null);
    }

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
                createdAt,
                null,
                null,
                null,
                List.of(),
                true,
                "medium"
        );
    }
}
