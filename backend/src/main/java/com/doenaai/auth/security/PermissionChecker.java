package com.doenaai.auth.security;

import com.doenaai.user.entity.Profile;
import com.doenaai.user.repository.ProfileRepository;
import com.doenaai.user.repository.UserAccessRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PermissionChecker {

    private final ProfileRepository profileRepository;
    private final UserAccessRepository userAccessRepository;

    public boolean has(
            Authentication authentication,
            String permission
    ) {

        if (authentication == null ||
                !authentication.isAuthenticated()) {
            return false;
        }

        if (!(authentication.getPrincipal() instanceof Jwt jwt)) {
            return false;
        }

        UUID userId;

        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException exception) {
            return false;
        }

        Profile profile = profileRepository
                .findById(userId)
                .orElse(null);

        if (profile == null ||
                !"ACTIVE".equals(profile.getStatus())) {
            return false;
        }

        Set<String> permissions =
                userAccessRepository.findPermissions(userId);

        return permissions.contains(permission);
    }
}