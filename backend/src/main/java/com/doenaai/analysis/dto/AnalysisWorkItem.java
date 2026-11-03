package com.doenaai.analysis.dto;

import java.util.UUID;

/**
 * Internal queue record. Never includes Git or database credentials.
 * evidenceSelection은 화면에서 고른 근거 전달 방식(ALWAYS·OFF)이며, 없으면 기본 동작이다.
 */
public record AnalysisWorkItem(UUID jobId, UUID projectId, UUID requirementVersionId,
                               UUID requestedBy, String repositoryUrl, String branch,
                               String commitSha, UUID reuseFromJobId, String evidenceSelection) {
    public AnalysisWorkItem(UUID jobId, UUID projectId, UUID requirementVersionId,
                            UUID requestedBy, String repositoryUrl, String branch,
                            String commitSha, UUID reuseFromJobId) {
        this(jobId, projectId, requirementVersionId, requestedBy, repositoryUrl, branch, commitSha, reuseFromJobId, null);
    }

    public AnalysisWorkItem(UUID jobId, UUID projectId, UUID requirementVersionId,
                            UUID requestedBy, String repositoryUrl, String branch, String commitSha) {
        this(jobId, projectId, requirementVersionId, requestedBy, repositoryUrl, branch, commitSha, null, null);
    }
}
