package com.doenaai.organization.controller;

import com.doenaai.organization.dto.OrganizationCreateRequest;
import com.doenaai.organization.dto.OrganizationMemberCreateRequest;
import com.doenaai.organization.dto.OrganizationMemberResponse;
import com.doenaai.organization.dto.OrganizationMemberUpdateRequest;
import com.doenaai.organization.dto.OrganizationResponse;
import com.doenaai.organization.service.OrganizationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService organizationService;

    @PreAuthorize(
            "@permissionChecker.has(authentication, 'ORGANIZATION_MANAGE')"
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrganizationResponse createOrganization(
            @Valid @RequestBody OrganizationCreateRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return organizationService.createOrganization(
                request,
                getUserId(jwt)
        );
    }

    @PreAuthorize(
            "@permissionChecker.has(authentication, 'ORGANIZATION_READ')"
    )
    @GetMapping
    public List<OrganizationResponse> getOrganizations(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return organizationService.getOrganizations(
                getUserId(jwt)
        );
    }

    @PreAuthorize(
            "@permissionChecker.has(authentication, 'ORGANIZATION_READ')"
    )
    @GetMapping("/{organizationId}")
    public OrganizationResponse getOrganization(
            @PathVariable UUID organizationId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return organizationService.getOrganization(
                organizationId,
                getUserId(jwt)
        );
    }

    @PreAuthorize(
            "@permissionChecker.has(authentication, 'ORGANIZATION_READ')"
    )
    @GetMapping("/{organizationId}/members")
    public List<OrganizationMemberResponse> getMembers(
            @PathVariable UUID organizationId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return organizationService.getMembers(
                organizationId,
                getUserId(jwt)
        );
    }

    @PreAuthorize(
            "@permissionChecker.has(authentication, 'ORGANIZATION_MANAGE')"
    )
    @PostMapping("/{organizationId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public OrganizationMemberResponse addMember(
            @PathVariable UUID organizationId,
            @Valid @RequestBody OrganizationMemberCreateRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return organizationService.addMember(
                organizationId,
                request,
                getUserId(jwt)
        );
    }

    @PreAuthorize(
            "@permissionChecker.has(authentication, 'ORGANIZATION_MANAGE')"
    )
    @PutMapping("/{organizationId}/members/{userId}")
    public OrganizationMemberResponse updateMember(
            @PathVariable UUID organizationId,
            @PathVariable UUID userId,
            @Valid @RequestBody OrganizationMemberUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return organizationService.updateMember(
                organizationId,
                userId,
                request,
                getUserId(jwt)
        );
    }

    @PreAuthorize(
            "@permissionChecker.has(authentication, 'ORGANIZATION_MANAGE')"
    )
    @DeleteMapping("/{organizationId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMember(
            @PathVariable UUID organizationId,
            @PathVariable UUID userId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        organizationService.deleteMember(
                organizationId,
                userId,
                getUserId(jwt)
        );
    }

    private UUID getUserId(
            Jwt jwt
    ) {
        return UUID.fromString(jwt.getSubject());
    }
}
