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

    @GetMapping("/me")
    public CurrentUserResponse me(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return currentUserService.getCurrentUser(jwt);
    }
}