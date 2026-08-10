package com.doenaai.requirement.dto;

import com.doenaai.requirement.entity.RequirementStatus;
import jakarta.validation.constraints.NotNull;

public record RequirementStatusUpdateRequest(
        @NotNull RequirementStatus status,
        Boolean customerVisible
) {
}
