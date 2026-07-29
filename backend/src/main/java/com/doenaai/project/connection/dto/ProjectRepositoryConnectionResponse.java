package com.doenaai.project.connection.dto;

import com.doenaai.project.connection.entity.*;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ProjectRepositoryConnectionResponse(
        UUID id,
        UUID projectId,
        RepositoryProviderType providerType,
        RepositoryAuthType authType,
        String username,
        RepositoryConnectionStatus status,
        String verifiedCommitSha,
        OffsetDateTime lastTestedAt,
        ConnectionTestResult lastTestResult,
        String lastErrorMessage,
        boolean credentialsConfigured,
        boolean knownHostsConfigured,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static ProjectRepositoryConnectionResponse from(
            ProjectRepositoryConnection connection
    ) {
        return new ProjectRepositoryConnectionResponse(
                connection.getId(),
                connection.getProjectId(),
                connection.getProviderType(),
                connection.getAuthType(),
                connection.getUsername(),
                connection.getStatus(),
                connection.getVerifiedCommitSha(),
                connection.getLastTestedAt(),
                connection.getLastTestResult(),
                connection.getLastErrorMessage(),
                connection.getCredentialCiphertext() != null,
                connection.getKnownHostsEntry() != null,
                connection.getCreatedAt(),
                connection.getUpdatedAt()
        );
    }
}
