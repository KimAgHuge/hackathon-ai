package com.doenaai.project.dto;

import com.doenaai.project.entity.ProjectStatus;
import com.doenaai.project.entity.ProjectSourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ProjectUpdateRequest(

        @NotNull
        UUID organizationId,

        @NotBlank
        @Size(max = 200)
        String name,

        String description,

        @Size(max = 2000)
        String repositoryUrl,

        @NotBlank
        @Size(max = 200)
        String defaultBranch,

        ProjectSourceType sourceType,

        @NotNull
        ProjectStatus status

) {
}
