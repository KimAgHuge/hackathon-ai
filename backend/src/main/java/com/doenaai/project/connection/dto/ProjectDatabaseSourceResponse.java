package com.doenaai.project.connection.dto;

import com.doenaai.project.connection.entity.*;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ProjectDatabaseSourceResponse(
        UUID id,
        UUID projectId,
        ConnectionMode connectionMode,
        DatabaseType dbType,
        String host,
        Integer port,
        String databaseName,
        String schemaName,
        String username,
        DatabaseSourceStatus status,
        OffsetDateTime lastTestedAt,
        ConnectionTestResult lastTestResult,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static ProjectDatabaseSourceResponse from(
            ProjectDatabaseSource source
    ) {
        return new ProjectDatabaseSourceResponse(
                source.getId(),
                source.getProjectId(),
                source.getConnectionMode(),
                source.getDbType(),
                source.getHost(),
                source.getPort(),
                source.getDatabaseName(),
                source.getSchemaName(),
                source.getUsername(),
                source.getStatus(),
                source.getLastTestedAt(),
                source.getLastTestResult(),
                source.getCreatedAt(),
                source.getUpdatedAt()
        );
    }
}
