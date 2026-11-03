package com.doenaai.analysis.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 저장된 작업의 목록 표시용 요약. 원본 JSON은 개별 결과 API에서만 조회한다. */
public record AnalysisHistoryItem(
        UUID jobId,
        UUID projectId,
        String projectName,
        UUID requirementVersionId,
        String requirementTitle,
        String status,
        String riskLevel,
        String summary,
        boolean approved,
        String reviewStatus,
        UUID evidenceReusedFromJobId,
        boolean reusableEvidence,
        List<String> evidenceUnavailableReasons,
        String sourceCommit,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt,
        Boolean thinkingEnabled,
        String thinkingEffort,
        List<String> evidenceWarnings
) {
    public AnalysisHistoryItem(UUID jobId, UUID projectId, String projectName, UUID requirementVersionId,
                               String requirementTitle, String status, String riskLevel, String summary,
                               boolean approved, String reviewStatus, UUID evidenceReusedFromJobId,
                               boolean reusableEvidence, List<String> evidenceUnavailableReasons,
                               String sourceCommit, OffsetDateTime createdAt, OffsetDateTime completedAt,
                               Boolean thinkingEnabled, String thinkingEffort) {
        this(jobId, projectId, projectName, requirementVersionId, requirementTitle, status, riskLevel,
                summary, approved, reviewStatus, evidenceReusedFromJobId, reusableEvidence,
                evidenceUnavailableReasons, sourceCommit, createdAt, completedAt, thinkingEnabled, thinkingEffort, List.of());
    }

    public AnalysisHistoryItem(UUID jobId, UUID projectId, String projectName, UUID requirementVersionId,
                               String requirementTitle, String status, String riskLevel, String summary,
                               boolean approved, String reviewStatus, UUID evidenceReusedFromJobId,
                               boolean reusableEvidence, List<String> evidenceUnavailableReasons,
                               String sourceCommit, OffsetDateTime createdAt, OffsetDateTime completedAt) {
        this(jobId, projectId, projectName, requirementVersionId, requirementTitle, status, riskLevel,
                summary, approved, reviewStatus, evidenceReusedFromJobId, reusableEvidence,
                evidenceUnavailableReasons, sourceCommit, createdAt, completedAt, null, null, List.of());
    }
}
