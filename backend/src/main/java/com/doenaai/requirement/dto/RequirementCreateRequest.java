package com.doenaai.requirement.dto;

import com.doenaai.requirement.entity.RequirementPriority;
import com.doenaai.requirement.entity.RequirementSourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RequirementCreateRequest(

        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{0,49}$")
        String requirementKey,

        @NotBlank
        @Size(max = 300)
        String title,

        RequirementSourceType sourceType,

        @Size(max = 500)
        String sourceRef,

        RequirementPriority priority,

        boolean customerVisible,

        @NotBlank
        String originalContent,

        String refinedContent,

        List<Object> acceptanceCriteria,

        List<Object> attachmentsMetadata,

        String changeNote
) {
}
