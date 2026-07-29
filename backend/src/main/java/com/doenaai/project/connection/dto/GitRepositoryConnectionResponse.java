package com.doenaai.project.connection.dto;

import com.doenaai.project.connection.entity.RepositoryAuthType;
import com.doenaai.project.connection.entity.RepositoryProviderType;

public record GitRepositoryConnectionResponse(
        boolean success,
        String message,
        String repositoryUrl,
        String branch,
        String commitSha,
        RepositoryProviderType providerType,
        RepositoryAuthType authType
) {

    public static GitRepositoryConnectionResponse success(
            String repositoryUrl,
            String branch,
            String commitSha,
            RepositoryProviderType providerType,
            RepositoryAuthType authType
    ) {
        return new GitRepositoryConnectionResponse(
                true,
                "Git 저장소와 브랜치 연결에 성공했습니다.",
                repositoryUrl,
                branch,
                commitSha,
                providerType,
                authType
        );
    }

    public static GitRepositoryConnectionResponse failure(
            String repositoryUrl,
            String branch,
            String message,
            RepositoryProviderType providerType,
            RepositoryAuthType authType
    ) {
        return new GitRepositoryConnectionResponse(
                false,
                message,
                repositoryUrl,
                branch,
                null,
                providerType,
                authType
        );
    }
}
