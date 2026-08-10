package com.doenaai.organization.dto;

import com.doenaai.organization.entity.OrganizationMember;
import com.doenaai.organization.entity.OrganizationMemberRole;
import com.doenaai.organization.entity.OrganizationMemberStatus;
import com.doenaai.user.entity.Profile;

import java.time.OffsetDateTime;
import java.util.UUID;

public record OrganizationMemberResponse(
        UUID organizationId,
        UUID userId,
        String displayName,
        String department,
        String jobTitle,
        OrganizationMemberRole memberRole,
        OrganizationMemberStatus status,
        UUID assignedBy,
        OffsetDateTime joinedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static OrganizationMemberResponse from(
            OrganizationMember member,
            Profile profile
    ) {
        return new OrganizationMemberResponse(
                member.getOrganizationId(),
                member.getUserId(),
                profile == null ? null : profile.getDisplayName(),
                profile == null ? null : profile.getDepartment(),
                profile == null ? null : profile.getJobTitle(),
                member.getMemberRole(),
                member.getStatus(),
                member.getAssignedBy(),
                member.getJoinedAt(),
                member.getCreatedAt(),
                member.getUpdatedAt()
        );
    }
}
