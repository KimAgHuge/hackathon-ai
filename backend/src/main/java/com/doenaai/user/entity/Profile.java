package com.doenaai.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Entity
@Table(name = "profiles", schema = "public")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Profile {

    @Id
    private UUID id;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(length = 100)
    private String department;

    @Column(name = "job_title", length = 100)
    private String jobTitle;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}