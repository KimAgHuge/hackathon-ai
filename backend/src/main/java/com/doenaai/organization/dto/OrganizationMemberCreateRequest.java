package com.doenaai.organization.dto;

import com.doenaai.organization.entity.OrganizationMemberRole;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record OrganizationMemberCreateRequest(

        @NotNull
        UUID userId,

        @NotNull
        OrganizationMemberRole memberRole

) {
}
