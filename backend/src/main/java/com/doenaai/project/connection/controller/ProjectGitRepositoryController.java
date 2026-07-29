package com.doenaai.project.connection.controller;

import com.doenaai.project.connection.dto.GitRepositoryConnectionRequest;
import com.doenaai.project.connection.dto.GitRepositoryConnectionResponse;
import com.doenaai.project.connection.dto.ProjectRepositoryConnectionResponse;
import com.doenaai.project.connection.service.GitRepositoryConnectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(
        "/api/projects/{projectId}/repository-connection"
)
@RequiredArgsConstructor
public class ProjectGitRepositoryController {

    private final GitRepositoryConnectionService
            gitRepositoryConnectionService;

    @PreAuthorize(
            "@permissionChecker.has(authentication, 'PROJECT_UPDATE')"
    )
    @PostMapping("/test")
    public GitRepositoryConnectionResponse testConnection(
            @PathVariable UUID projectId,
            @Valid @RequestBody GitRepositoryConnectionRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return gitRepositoryConnectionService.testConnection(
                projectId,
                request,
                getUserId(jwt)
        );
    }

    @PreAuthorize(
            "@permissionChecker.has(authentication, 'PROJECT_UPDATE')"
    )
    @PutMapping
    public ProjectRepositoryConnectionResponse saveConnection(
            @PathVariable UUID projectId,
            @Valid @RequestBody GitRepositoryConnectionRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return gitRepositoryConnectionService.saveConnection(
                projectId,
                request,
                getUserId(jwt)
        );
    }

    @PreAuthorize(
            "@permissionChecker.has(authentication, 'PROJECT_READ')"
    )
    @GetMapping
    public ProjectRepositoryConnectionResponse getConnection(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return gitRepositoryConnectionService.getConnection(
                projectId,
                getUserId(jwt)
        );
    }

    private UUID getUserId(
            Jwt jwt
    ) {
        return UUID.fromString(
                jwt.getSubject()
        );
    }
}
