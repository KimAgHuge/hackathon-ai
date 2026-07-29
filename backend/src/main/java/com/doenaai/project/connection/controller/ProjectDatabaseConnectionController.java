package com.doenaai.project.connection.controller;

import com.doenaai.project.connection.dto.DatabaseConnectionRequest;
import com.doenaai.project.connection.dto.DatabaseConnectionTestResponse;
import com.doenaai.project.connection.dto.ProjectDatabaseSourceResponse;
import com.doenaai.project.connection.service.DatabaseConnectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(
        "/api/projects/{projectId}/database-connection"
)
@RequiredArgsConstructor
public class ProjectDatabaseConnectionController {

    private final DatabaseConnectionService databaseConnectionService;

    @PreAuthorize(
            "@permissionChecker.has(authentication, 'PROJECT_UPDATE')"
    )
    @PostMapping("/test")
    public DatabaseConnectionTestResponse testConnection(
            @PathVariable UUID projectId,
            @Valid @RequestBody DatabaseConnectionRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return databaseConnectionService.testConnection(
                projectId,
                request,
                getUserId(jwt)
        );
    }

    @PreAuthorize(
            "@permissionChecker.has(authentication, 'PROJECT_UPDATE')"
    )
    @PutMapping
    public ProjectDatabaseSourceResponse saveConnection(
            @PathVariable UUID projectId,
            @Valid @RequestBody DatabaseConnectionRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return databaseConnectionService.saveConnection(
                projectId,
                request,
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
