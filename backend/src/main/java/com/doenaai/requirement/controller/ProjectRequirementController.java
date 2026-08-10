package com.doenaai.requirement.controller;

import com.doenaai.requirement.dto.RequirementCreateRequest;
import com.doenaai.requirement.dto.RequirementResponse;
import com.doenaai.requirement.service.RequirementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/requirements")
@RequiredArgsConstructor
public class ProjectRequirementController {

    private final RequirementService requirementService;

    @PreAuthorize("isAuthenticated()")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RequirementResponse createRequirement(
            @PathVariable UUID projectId,
            @Valid @RequestBody RequirementCreateRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return requirementService.createRequirement(
                projectId,
                request,
                getUserId(jwt)
        );
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public List<RequirementResponse> getRequirements(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return requirementService.getRequirements(
                projectId,
                getUserId(jwt)
        );
    }

    private UUID getUserId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
