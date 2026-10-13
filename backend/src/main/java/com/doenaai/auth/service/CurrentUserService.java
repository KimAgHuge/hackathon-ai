package com.doenaai.auth.service;

import com.doenaai.auth.dto.CurrentUserResponse;
import com.doenaai.user.entity.Profile;
import com.doenaai.user.repository.ProfileRepository;
import com.doenaai.user.repository.UserAccessRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final ProfileRepository profileRepository;
    private final UserAccessRepository userAccessRepository;

    /** JWT의 사용자 ID로 활성 프로필·역할·권한을 조회해 /auth/me 응답을 만든다. */
    @Transactional(readOnly = true)
    public CurrentUserResponse getCurrentUser(Jwt jwt) {

        UUID userId;

        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException exception) {
            throw new AccessDeniedException(
                    "유효하지 않은 사용자 식별자입니다."
            );
        }

        Profile profile = profileRepository
                .findById(userId)
                .orElseThrow(() ->
                        new AccessDeniedException(
                                "사용자 프로필을 찾을 수 없습니다."
                        )
                );

        if (!"ACTIVE".equals(profile.getStatus())) {
            throw new AccessDeniedException(
                    "사용할 수 없는 사용자 계정입니다."
            );
        }

        Set<String> roles =
                userAccessRepository.findRoles(userId);

        Set<String> permissions =
                userAccessRepository.findPermissions(userId);

        return new CurrentUserResponse(
                userId,
                jwt.getClaimAsString("email"),
                profile.getDisplayName(),
                profile.getDepartment(),
                profile.getJobTitle(),
                profile.getStatus(),
                roles,
                permissions
        );
    }
}
