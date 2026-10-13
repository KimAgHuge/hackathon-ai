package com.doenaai.auth.controller;

import com.doenaai.auth.dto.CurrentUserResponse;
import com.doenaai.auth.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final CurrentUserService currentUserService;

    /** 인증된 JWT를 받아 화면에서 사용할 프로필·역할·권한을 반환한다. */
    @GetMapping("/me")
    public CurrentUserResponse me(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return currentUserService.getCurrentUser(jwt);
    }
}
