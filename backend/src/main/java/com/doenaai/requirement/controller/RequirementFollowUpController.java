package com.doenaai.requirement.controller;

import com.doenaai.requirement.dto.RequirementFollowUpRequest;
import com.doenaai.requirement.dto.RequirementVersionResponse;
import com.doenaai.requirement.service.RequirementFollowUpService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/requirements")
@RequiredArgsConstructor
public class RequirementFollowUpController {
    private final RequirementFollowUpService followUps;

    @PostMapping("/{requirementId}/follow-ups")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public RequirementVersionResponse create(@PathVariable UUID requirementId,
                                              @Valid @RequestBody RequirementFollowUpRequest request,
                                              @AuthenticationPrincipal Jwt jwt) {
        return followUps.create(requirementId, request, UUID.fromString(jwt.getSubject()));
    }
}
