package com.doenaai.project.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Getter
@Entity
@Table(name = "projects", schema = "public")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column
    private String description;

    @Column(name = "repository_url")
    private String repositoryUrl;

    @Column(name = "default_branch", nullable = false, length = 200)
    private String defaultBranch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProjectStatus status;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Project(
            String name,
            String description,
            String repositoryUrl,
            String defaultBranch,
            UUID createdBy
    ) {
        this.name = name;
        this.description = description;
        this.repositoryUrl = repositoryUrl;
        this.defaultBranch = defaultBranch == null || defaultBranch.isBlank()
                ? "main"
                : defaultBranch;
        this.status = ProjectStatus.ACTIVE;
        this.createdBy = createdBy;
    }

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public void update(
            String name,
            String description,
            String repositoryUrl,
            String defaultBranch,
            ProjectStatus status
    ) {
        this.name = name;
        this.description = description;
        this.repositoryUrl = repositoryUrl;
        this.defaultBranch = defaultBranch;
        this.status = status;
    }
}