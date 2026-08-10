package com.doenaai.organization.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
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
@IdClass(OrganizationMemberId.class)
@Table(name = "organization_members", schema = "public")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrganizationMember {

    @Id
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "member_role", nullable = false, length = 20)
    private OrganizationMemberRole memberRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrganizationMemberStatus status;

    @Column(name = "assigned_by")
    private UUID assignedBy;

    @Column(name = "joined_at", nullable = false)
    private OffsetDateTime joinedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public OrganizationMember(
            UUID organizationId,
            UUID userId,
            OrganizationMemberRole memberRole,
            UUID assignedBy
    ) {
        this.organizationId = organizationId;
        this.userId = userId;
        this.memberRole = memberRole;
        this.status = OrganizationMemberStatus.ACTIVE;
        this.assignedBy = assignedBy;
    }

    public void update(
            OrganizationMemberRole memberRole,
            OrganizationMemberStatus status,
            UUID assignedBy
    ) {
        this.memberRole = memberRole;
        this.status = status;
        this.assignedBy = assignedBy;
    }

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        this.joinedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
