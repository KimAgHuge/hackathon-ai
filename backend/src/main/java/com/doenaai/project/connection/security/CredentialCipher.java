package com.doenaai.project.connection.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

@Component
public class CredentialCipher {

    private static final int IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final String VERSION_PREFIX = "v1:";

    private final SecureRandom secureRandom =
            new SecureRandom();

    private final String encodedKey;

    public CredentialCipher(
            @Value(
                    "${doenaai.security.credential-encryption-key:}"
            )
            String encodedKey
    ) {
        this.encodedKey = encodedKey;
    }

    public String encrypt(
            String plaintext
    ) {
        SecretKeySpec key = getKey();
        byte[] iv = new byte[IV_LENGTH];
        secureRandom.nextBytes(iv);

        try {
            Cipher cipher = Cipher.getInstance(
                    "AES/GCM/NoPadding"
            );
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    key,
                    new GCMParameterSpec(
                            GCM_TAG_LENGTH_BITS,
                            iv
                    )
            );

            byte[] ciphertext = cipher.doFinal(
                    plaintext.getBytes(StandardCharsets.UTF_8)
            );
            byte[] payload = ByteBuffer
                    .allocate(iv.length + ciphertext.length)
                    .put(iv)
                    .put(ciphertext)
                    .array();

            return VERSION_PREFIX +
                    Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException exception) {
            throw new ResponseStatusException(
                    INTERNAL_SERVER_ERROR,
                    "연결 인증정보를 암호화하지 못했습니다.",
                    exception
            );
        }
    }

    public String decrypt(
            String encryptedValue
    ) {
        if (
                encryptedValue == null ||
                !encryptedValue.startsWith(VERSION_PREFIX)
        ) {
            throw new ResponseStatusException(
                    INTERNAL_SERVER_ERROR,
                    "저장된 연결 인증정보 형식이 올바르지 않습니다."
            );
        }

        SecretKeySpec key = getKey();
        byte[] payload;

        try {
            payload = Base64.getDecoder().decode(
                    encryptedValue.substring(
                            VERSION_PREFIX.length()
                    )
            );
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    INTERNAL_SERVER_ERROR,
                    "저장된 연결 인증정보를 읽지 못했습니다.",
                    exception
            );
        }

        if (payload.length <= IV_LENGTH) {
            throw new ResponseStatusException(
                    INTERNAL_SERVER_ERROR,
                    "저장된 연결 인증정보가 손상되었습니다."
            );
        }

        byte[] iv = new byte[IV_LENGTH];
        byte[] ciphertext =
                new byte[payload.length - IV_LENGTH];

        ByteBuffer.wrap(payload)
                .get(iv)
                .get(ciphertext);

        try {
            Cipher cipher = Cipher.getInstance(
                    "AES/GCM/NoPadding"
            );
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(
                            GCM_TAG_LENGTH_BITS,
                            iv
                    )
            );

            return new String(
                    cipher.doFinal(ciphertext),
                    StandardCharsets.UTF_8
            );
        } catch (GeneralSecurityException exception) {
            throw new ResponseStatusException(
                    INTERNAL_SERVER_ERROR,
                    "저장된 연결 인증정보를 복호화하지 못했습니다.",
                    exception
            );
        }
    }

    private SecretKeySpec getKey() {
        if (!StringUtils.hasText(encodedKey)) {
            throw new ResponseStatusException(
                INTERNAL_SERVER_ERROR,
                "연결 인증정보 암호화 키가 설정되지 않았습니다."
            );
        }

        byte[] keyBytes;

        try {
            keyBytes = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            throw invalidKey(exception);
        }

        if (keyBytes.length != 32) {
            throw invalidKey(null);
        }

        return new SecretKeySpec(
                keyBytes,
                "AES"
        );
    }

    private ResponseStatusException invalidKey(
            Exception cause
    ) {
        return new ResponseStatusException(
                INTERNAL_SERVER_ERROR,
                "연결 인증정보 암호화 키는 Base64로 인코딩된 32바이트 키여야 합니다.",
                cause
        );
    }
}
