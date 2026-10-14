package com.doenaai.project.controller;

import com.doenaai.project.dto.ProjectCreateRequest;
import com.doenaai.project.dto.ProjectResponse;
import com.doenaai.project.dto.ProjectUpdateRequest;
import com.doenaai.project.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @PreAuthorize(
            "@permissionChecker.has(authentication, 'PROJECT_READ')"
    )
    @GetMapping
    public List<ProjectResponse> getProjects(
            @RequestParam(defaultValue = "false") boolean deleted,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return deleted
                ? projectService.getDeletedProjects(getUserId(jwt))
                : projectService.getProjects(getUserId(jwt));
    }

    @PreAuthorize(
            "@permissionChecker.has(authentication, 'PROJECT_READ')"
    )
    @GetMapping("/{projectId}")
    public ProjectResponse getProject(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return projectService.getProject(
                projectId,
                getUserId(jwt)
        );
    }

    @PreAuthorize(
            "@permissionChecker.has(authentication, 'PROJECT_CREATE')"
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse createProject(
            @Valid @RequestBody ProjectCreateRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return projectService.createProject(
                request,
                getUserId(jwt)
        );
    }

    @PreAuthorize(
            "@permissionChecker.has(authentication, 'PROJECT_UPDATE')"
    )
    @PutMapping("/{projectId}")
    public ProjectResponse updateProject(
            @PathVariable UUID projectId,
            @Valid @RequestBody ProjectUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return projectService.updateProject(
                projectId,
                request,
                getUserId(jwt)
        );
    }

    @PreAuthorize("@permissionChecker.has(authentication, 'PROJECT_UPDATE')")
    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProject(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        projectService.deleteProject(projectId, getUserId(jwt));
    }

    @PreAuthorize("@permissionChecker.has(authentication, 'PROJECT_UPDATE')")
    @PostMapping("/{projectId}/restore")
    public ProjectResponse restoreProject(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return projectService.restoreProject(projectId, getUserId(jwt));
    }

    private UUID getUserId(
            Jwt jwt
    ) {
        return UUID.fromString(
                jwt.getSubject()
        );
    }
}
