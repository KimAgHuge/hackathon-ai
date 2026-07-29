package com.doenaai.project.connection.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Getter
@Entity
@Table(
        name = "project_repository_connections",
        schema = "public"
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectRepositoryConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false, unique = true)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false, length = 20)
    private RepositoryProviderType providerType;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 30)
    private RepositoryAuthType authType;

    @Column(length = 200)
    private String username;

    @Column(name = "credential_ciphertext")
    private String credentialCiphertext;

    @Column(name = "known_hosts_entry")
    private String knownHostsEntry;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RepositoryConnectionStatus status;

    @Column(name = "verified_commit_sha", length = 64)
    private String verifiedCommitSha;

    @Column(name = "last_tested_at")
    private OffsetDateTime lastTestedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_test_result", length = 20)
    private ConnectionTestResult lastTestResult;

    @Column(name = "last_error_message")
    private String lastErrorMessage;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public ProjectRepositoryConnection(
            UUID projectId,
            UUID createdBy
    ) {
        this.projectId = projectId;
        this.createdBy = createdBy;
        this.providerType = RepositoryProviderType.AUTO;
        this.authType = RepositoryAuthType.NONE;
        this.status = RepositoryConnectionStatus.PENDING;
    }

    public void markConnected(
            RepositoryProviderType providerType,
            RepositoryAuthType authType,
            String username,
            String credentialCiphertext,
            String knownHostsEntry,
            String commitSha
    ) {
        this.providerType = providerType;
        this.authType = authType;
        this.username = username;
        this.credentialCiphertext = credentialCiphertext;
        this.knownHostsEntry = knownHostsEntry;
        this.status = RepositoryConnectionStatus.CONNECTED;
        this.verifiedCommitSha = commitSha;
        this.lastTestedAt = OffsetDateTime.now(
                ZoneOffset.UTC
        );
        this.lastTestResult = ConnectionTestResult.SUCCESS;
        this.lastErrorMessage = null;
    }

    public void markFailed(
            String message
    ) {
        this.status = RepositoryConnectionStatus.FAILED;
        this.verifiedCommitSha = null;
        this.lastTestedAt = OffsetDateTime.now(
                ZoneOffset.UTC
        );
        this.lastTestResult = ConnectionTestResult.FAILED;
        this.lastErrorMessage = message;
    }

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(
                ZoneOffset.UTC
        );

        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now(
                ZoneOffset.UTC
        );
    }
}
