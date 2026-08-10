package com.doenaai.requirement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Getter
@Entity
@Table(name = "requirements", schema = "public")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Requirement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "requirement_key", length = 50)
    private String requirementKey;

    @Column(nullable = false, length = 300)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    private RequirementSourceType sourceType;

    @Column(name = "source_ref", length = 500)
    private String sourceRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RequirementPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RequirementStatus status;

    @Column(name = "customer_visible", nullable = false)
    private boolean customerVisible;

    @Column(name = "requested_by")
    private UUID requestedBy;

    @Column(name = "customer_confirmed_by")
    private UUID customerConfirmedBy;

    @Column(name = "customer_confirmed_at")
    private OffsetDateTime customerConfirmedAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Requirement(
            UUID organizationId,
            UUID projectId,
            String requirementKey,
            String title,
            RequirementSourceType sourceType,
            String sourceRef,
            RequirementPriority priority,
            boolean customerVisible,
            UUID createdBy
    ) {
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.requirementKey = normalize(requirementKey);
        this.title = title;
        this.sourceType = sourceType == null
                ? RequirementSourceType.CUSTOMER
                : sourceType;
        this.sourceRef = normalize(sourceRef);
        this.priority = priority == null
                ? RequirementPriority.MEDIUM
                : priority;
        this.status = RequirementStatus.DRAFT;
        this.customerVisible = customerVisible;
        this.requestedBy = createdBy;
        this.createdBy = createdBy;
    }

    public void updateCurrentTitle(String title) {
        this.title = title;
    }

    public void updateStatus(
            RequirementStatus status,
            Boolean customerVisible
    ) {
        this.status = status;

        if (customerVisible != null) {
            this.customerVisible = customerVisible;
        }
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

    private String normalize(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }
}
