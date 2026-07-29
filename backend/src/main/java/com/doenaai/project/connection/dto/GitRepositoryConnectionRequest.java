package com.doenaai.project.connection.dto;

import com.doenaai.project.connection.entity.RepositoryAuthType;
import com.doenaai.project.connection.entity.RepositoryProviderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record GitRepositoryConnectionRequest(

        @NotBlank
        @Size(max = 2000)
        String repositoryUrl,

        @NotBlank
        @Size(max = 200)
        String branch,

        @NotNull
        RepositoryProviderType providerType,

        @NotNull
        RepositoryAuthType authType,

        @Size(max = 200)
        String username,

        @Size(max = 5000)
        String secret,

        @Size(max = 100000)
        String sshPrivateKey,

        @Size(max = 5000)
        String sshPassphrase,

        @Size(max = 10000)
        String knownHostsEntry

) {
}
