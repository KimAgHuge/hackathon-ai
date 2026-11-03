package com.doenaai.analysis.dto;

import java.util.UUID;

/**
 * Git·DB 자격증명을 제외한 내부 대기열 입력이다.
 * evidenceSelection은 화면에서 고른 근거 전달 방식(ALWAYS·OFF)이며, 없으면 기본 동작이다.
 */
public record AnalysisWorkItem(UUID jobId, UUID projectId, UUID requirementVersionId,
                               UUID requestedBy, String repositoryUrl, String branch,
                               String commitSha, UUID reuseFromJobId, Boolean thinkingEnabled,
                               String thinkingEffort, String evidenceSelection) {
    public AnalysisWorkItem(UUID jobId, UUID projectId, UUID requirementVersionId,
                            UUID requestedBy, String repositoryUrl, String branch, String commitSha,
                            UUID reuseFromJobId, Boolean thinkingEnabled, String thinkingEffort) {
        this(jobId, projectId, requirementVersionId, requestedBy, repositoryUrl, branch, commitSha,
                reuseFromJobId, thinkingEnabled, thinkingEffort, null);
    }

    public AnalysisWorkItem(UUID jobId, UUID projectId, UUID requirementVersionId,
                            UUID requestedBy, String repositoryUrl, String branch, String commitSha,
                            UUID reuseFromJobId, Boolean thinkingEnabled) {
        this(jobId, projectId, requirementVersionId, requestedBy, repositoryUrl, branch, commitSha,
                reuseFromJobId, thinkingEnabled, thinkingEnabled == null ? null : "medium", null);
    }

    public AnalysisWorkItem(UUID jobId, UUID projectId, UUID requirementVersionId,
                            UUID requestedBy, String repositoryUrl, String branch,
                            String commitSha, UUID reuseFromJobId) {
        this(jobId, projectId, requirementVersionId, requestedBy, repositoryUrl, branch, commitSha,
                reuseFromJobId, true, "medium", null);
    }

    public AnalysisWorkItem(UUID jobId, UUID projectId, UUID requirementVersionId,
                            UUID requestedBy, String repositoryUrl, String branch, String commitSha) {
        this(jobId, projectId, requirementVersionId, requestedBy, repositoryUrl, branch, commitSha,
                null, true, "medium", null);
    }
}
