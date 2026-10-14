package com.doenaai.requirement.entity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record RequirementVersion(
        UUID id,
        UUID requirementId,
        int versionNumber,
        String title,
        String originalContent,
        String refinedContent,
        List<Object> acceptanceCriteria,
        List<Object> attachmentsMetadata,
        String changeNote,
        List<Object> clarifications,
        UUID sourceAnalysisJobId,
        UUID parentVersionId,
        boolean current,
        UUID createdBy,
        OffsetDateTime createdAt
) {
}
