package com.doenaai.project.connection.security;

public record RepositoryCredentialPayload(
        String secret,
        String privateKey,
        String passphrase
) {
}
