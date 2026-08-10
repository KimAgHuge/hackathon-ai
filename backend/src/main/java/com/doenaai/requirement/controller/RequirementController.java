package com.doenaai.requirement.controller;

import com.doenaai.requirement.dto.RequirementResponse;
import com.doenaai.requirement.dto.RequirementStatusUpdateRequest;
import com.doenaai.requirement.dto.RequirementVersionCreateRequest;
import com.doenaai.requirement.dto.RequirementVersionResponse;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/requirements")
@RequiredArgsConstructor
public class RequirementController {

    private final RequirementService requirementService;

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{requirementId}")
    public RequirementResponse getRequirement(
            @PathVariable UUID requirementId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return requirementService.getRequirement(
                requirementId,
                getUserId(jwt)
        );
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{requirementId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public RequirementVersionResponse createVersion(
            @PathVariable UUID requirementId,
            @Valid @RequestBody RequirementVersionCreateRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return requirementService.createVersion(
                requirementId,
                request,
                getUserId(jwt)
        );
    }

    @PreAuthorize("isAuthenticated()")
    @PutMapping("/{requirementId}/status")
    public RequirementResponse updateStatus(
            @PathVariable UUID requirementId,
            @Valid @RequestBody RequirementStatusUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return requirementService.updateStatus(
                requirementId,
                request,
                getUserId(jwt)
        );
    }

    private UUID getUserId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
