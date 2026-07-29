package com.doenaai.project.dto;

import com.doenaai.project.entity.Project;
import com.doenaai.project.entity.ProjectStatus;
import com.doenaai.project.entity.ProjectSourceType;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        String name,
        String description,
        String repositoryUrl,
        String defaultBranch,
        ProjectSourceType sourceType,
        String sourceFileName,
        ProjectStatus status,
        UUID createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static ProjectResponse from(Project project) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getDescription(),
                project.getRepositoryUrl(),
                project.getDefaultBranch(),
                project.getSourceType(),
                project.getSourceFileName(),
                project.getStatus(),
                project.getCreatedBy(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }
}
