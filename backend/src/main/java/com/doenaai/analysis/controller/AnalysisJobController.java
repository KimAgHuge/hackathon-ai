package com.doenaai.analysis.controller;

import com.doenaai.analysis.dto.AnalysisJobCreateRequest;
import com.doenaai.analysis.dto.AnalysisJobResponse;
import com.doenaai.analysis.service.AnalysisJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class AnalysisJobController {

    private final AnalysisJobService analysisJobService;

    @PreAuthorize(
            "@permissionChecker.has(authentication, 'PROJECT_UPDATE')"
    )
    @PostMapping(
            "/api/projects/{projectId}/analysis-jobs"
    )
    @ResponseStatus(HttpStatus.CREATED)
    public AnalysisJobResponse createProjectScan(
            @PathVariable UUID projectId,
            @RequestBody(required = false)
            AnalysisJobCreateRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return analysisJobService.createProjectScan(
                projectId,
                request == null
                        ? null
                        : request.requirementVersionId(),
                getUserId(jwt)
        );
    }

    @PreAuthorize(
            "@permissionChecker.has(authentication, 'ANALYSIS_READ')"
    )
    @GetMapping(
            "/api/analysis-jobs/{analysisJobId}"
    )
    public AnalysisJobResponse getAnalysisJob(
            @PathVariable UUID analysisJobId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return analysisJobService.getAnalysisJob(
                analysisJobId,
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
