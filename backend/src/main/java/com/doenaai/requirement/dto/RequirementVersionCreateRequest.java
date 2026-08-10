package com.doenaai.requirement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RequirementVersionCreateRequest(

        @NotBlank
        @Size(max = 300)
        String title,

        @NotBlank
        String originalContent,

        String refinedContent,

        List<Object> acceptanceCriteria,

        List<Object> attachmentsMetadata,

        String changeNote
) {
}
