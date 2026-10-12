package com.doenaai.analysis.dto;

import java.util.UUID;

/** Internal queue record. Never includes Git or database credentials. */
public record AnalysisWorkItem(UUID jobId, UUID projectId, UUID requirementVersionId,
                               UUID requestedBy, String repositoryUrl, String branch,
                               String commitSha) {
}
