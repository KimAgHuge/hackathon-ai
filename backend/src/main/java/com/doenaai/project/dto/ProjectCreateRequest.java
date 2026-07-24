package com.doenaai.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProjectCreateRequest(

        @NotBlank
        @Size(max = 200)
        String name,

        String description,

        @Size(max = 2000)
        String repositoryUrl,

        @Size(max = 200)
        String defaultBranch

) {
}