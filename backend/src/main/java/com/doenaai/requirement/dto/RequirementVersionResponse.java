package com.doenaai.requirement.dto;

import com.doenaai.requirement.entity.RequirementVersion;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record RequirementVersionResponse(
        UUID id,
        UUID requirementId,
        int versionNumber,
        String title,
        String originalContent,
        String refinedContent,
        List<Object> acceptanceCriteria,
        List<Object> attachmentsMetadata,
        String changeNote,
        boolean current,
        UUID createdBy,
        OffsetDateTime createdAt
) {

    public static RequirementVersionResponse from(
            RequirementVersion version,
            boolean customerView
    ) {
        return new RequirementVersionResponse(
                version.id(),
                version.requirementId(),
                version.versionNumber(),
                version.title(),
                version.originalContent(),
                version.refinedContent(),
                version.acceptanceCriteria(),
                customerView
                        ? List.of()
                        : version.attachmentsMetadata(),
                customerView ? null : version.changeNote(),
                version.current(),
                customerView ? null : version.createdBy(),
                version.createdAt()
        );
    }
}
