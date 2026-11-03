package com.doenaai.analysis.controller;

import com.doenaai.analysis.dto.AnalysisJobCreateRequest;
import com.doenaai.analysis.dto.AnalysisJobResponse;
import com.doenaai.analysis.dto.AnalysisHistoryPage;
import com.doenaai.analysis.service.AnalysisJobService;
import com.doenaai.analysis.service.AnalysisHistoryService;
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
    private final AnalysisHistoryService analysisHistoryService;

    /** 접근 가능한 프로젝트의 저장된 분석 작업을 최신순으로 반환한다. */
    @GetMapping("/api/analysis-jobs")
    @PreAuthorize("@permissionChecker.has(authentication, 'ANALYSIS_READ')")
    public AnalysisHistoryPage list(
            @RequestParam(required = false) UUID projectId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal Jwt jwt
    ) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "페이지 범위가 올바르지 않습니다.");
        }
        return analysisHistoryService.list(getUserId(jwt), projectId, page, size);
    }

    /** projectId와 선택적 requirementVersionId를 받아 대기 작업을 생성한다. */
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
                getUserId(jwt),
                request == null ? null : request.reuseFromJobId(),
                request != null && Boolean.TRUE.equals(request.confirmedUnchanged()),
                request == null ? null : request.evidenceSelection()
        );
    }

    /** analysisJobId의 현재 상태·진행률을 인증된 사용자에게 반환한다. */
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

    @PostMapping("/api/analysis-jobs/{analysisJobId}/cancel")
    @PreAuthorize("@permissionChecker.has(authentication, 'PROJECT_UPDATE')")
    public AnalysisJobResponse cancelAnalysisJob(@PathVariable UUID analysisJobId,
                                                 @AuthenticationPrincipal Jwt jwt) {
        return analysisJobService.cancelAnalysisJob(analysisJobId, getUserId(jwt));
    }

    private UUID getUserId(
            Jwt jwt
    ) {
        return UUID.fromString(
                jwt.getSubject()
        );
    }
}
