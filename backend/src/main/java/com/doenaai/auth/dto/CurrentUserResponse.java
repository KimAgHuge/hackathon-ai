package com.doenaai.auth.dto;

import java.util.Set;
import java.util.UUID;

public record CurrentUserResponse(
        UUID id,
        String email,
        String displayName,
        String department,
        String jobTitle,
        String status,
        Set<String> roles,
        Set<String> permissions
) {
}