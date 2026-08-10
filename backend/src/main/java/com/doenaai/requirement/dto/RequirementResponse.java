package com.doenaai.requirement.dto;

import com.doenaai.requirement.entity.Requirement;
import com.doenaai.requirement.entity.RequirementPriority;
import com.doenaai.requirement.entity.RequirementSourceType;
import com.doenaai.requirement.entity.RequirementStatus;
import com.doenaai.requirement.entity.RequirementVersion;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RequirementResponse(
        UUID id,
        UUID organizationId,
        UUID projectId,
        String requirementKey,
        String title,
        RequirementSourceType sourceType,
        String sourceRef,
        RequirementPriority priority,
        RequirementStatus status,
        boolean customerVisible,
        UUID requestedBy,
        UUID createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        RequirementVersionResponse currentVersion
) {

    public static RequirementResponse from(
            Requirement requirement,
            RequirementVersion version,
            boolean customerView
    ) {
        return new RequirementResponse(
                requirement.getId(),
                requirement.getOrganizationId(),
                requirement.getProjectId(),
                requirement.getRequirementKey(),
                requirement.getTitle(),
                requirement.getSourceType(),
                customerView ? null : requirement.getSourceRef(),
                requirement.getPriority(),
                requirement.getStatus(),
                requirement.isCustomerVisible(),
                customerView ? null : requirement.getRequestedBy(),
                customerView ? null : requirement.getCreatedBy(),
                requirement.getCreatedAt(),
                requirement.getUpdatedAt(),
                RequirementVersionResponse.from(
                        version,
                        customerView
                )
        );
    }
}
