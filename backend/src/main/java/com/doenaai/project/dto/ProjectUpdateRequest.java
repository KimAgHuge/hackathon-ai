package com.doenaai.project.dto;

import com.doenaai.project.entity.ProjectStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ProjectUpdateRequest(

        @NotBlank
        @Size(max = 200)
        String name,

        String description,

        @Size(max = 2000)
        String repositoryUrl,

        @NotBlank
        @Size(max = 200)
        String defaultBranch,

        @NotNull
        ProjectStatus status

) {
}