package com.doenaai.organization.dto;

import com.doenaai.organization.entity.OrganizationMemberRole;
import com.doenaai.organization.entity.OrganizationMemberStatus;
import jakarta.validation.constraints.NotNull;

public record OrganizationMemberUpdateRequest(

        @NotNull
        OrganizationMemberRole memberRole,

        @NotNull
        OrganizationMemberStatus status

) {
}
