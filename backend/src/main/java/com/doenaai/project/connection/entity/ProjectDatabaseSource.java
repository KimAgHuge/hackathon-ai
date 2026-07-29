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
@Table(name = "project_database_sources", schema = "public")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectDatabaseSource {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "connection_mode", nullable = false, length = 30)
    private ConnectionMode connectionMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "db_type", nullable = false, length = 30)
    private DatabaseType dbType;

    @Column(name = "is_primary", nullable = false)
    private boolean primaryConnection;

    @Column(length = 255)
    private String host;

    @Column
    private Integer port;

    @Column(name = "database_name", length = 200)
    private String databaseName;

    @Column(name = "schema_name", length = 200)
    private String schemaName;

    @Column(length = 200)
    private String username;

    @Column(name = "credential_ciphertext")
    private String credentialCiphertext;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DatabaseSourceStatus status;

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

    public ProjectDatabaseSource(
            UUID projectId,
            UUID createdBy
    ) {
        this.projectId = projectId;
        this.createdBy = createdBy;
        this.connectionMode = ConnectionMode.DB_CONNECTION;
        this.primaryConnection = true;
        this.status = DatabaseSourceStatus.PENDING;
    }

    public void updateConnection(
            DatabaseType dbType,
            String host,
            int port,
            String databaseName,
            String schemaName,
            String username,
            String credentialCiphertext
    ) {
        this.connectionMode = ConnectionMode.DB_CONNECTION;
        this.dbType = dbType;
        this.host = host;
        this.port = port;
        this.databaseName = databaseName;
        this.schemaName = schemaName;
        this.username = username;
        this.credentialCiphertext = credentialCiphertext;
        this.status = DatabaseSourceStatus.CONNECTED;
        this.lastTestedAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.lastTestResult = ConnectionTestResult.SUCCESS;
        this.lastErrorMessage = null;
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
}
