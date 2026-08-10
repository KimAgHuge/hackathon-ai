package com.doenaai.organization.dto;

import com.doenaai.organization.entity.Organization;
import com.doenaai.organization.entity.OrganizationMemberRole;
import com.doenaai.organization.entity.OrganizationStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record OrganizationResponse(
        UUID id,
        String name,
        String slug,
        String description,
        OrganizationStatus status,
        OrganizationMemberRole memberRole,
        UUID createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static OrganizationResponse from(
            Organization organization,
            OrganizationMemberRole memberRole
    ) {
        return new OrganizationResponse(
                organization.getId(),
                organization.getName(),
                organization.getSlug(),
                organization.getDescription(),
                organization.getStatus(),
                memberRole,
                organization.getCreatedBy(),
                organization.getCreatedAt(),
                organization.getUpdatedAt()
        );
    }
}
