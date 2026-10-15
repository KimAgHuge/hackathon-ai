package com.doenaai.analysis.controller;

import com.doenaai.analysis.dto.AnalysisResultResponse;
import com.doenaai.analysis.dto.AnalysisReviewRequest;
import com.doenaai.analysis.service.AnalysisResultService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class AnalysisResultController {
    private final AnalysisResultService results;

    /** 인증된 사용자가 접근 가능한 완료 작업의 AI 결과와 입력 근거를 반환한다. */
    @GetMapping("/api/analysis-jobs/{jobId}/result")
    @PreAuthorize("@permissionChecker.has(authentication, 'ANALYSIS_READ')")
    public AnalysisResultResponse get(@PathVariable UUID jobId, @AuthenticationPrincipal Jwt jwt) {
        return results.get(jobId, UUID.fromString(jwt.getSubject()));
    }

    /** 완료된 분석을 프로젝트 관리 권한과 분석 승인 권한이 있는 사람이 검토한다. */
    @PutMapping("/api/analysis-jobs/{jobId}/review")
    @PreAuthorize("@permissionChecker.has(authentication, 'ANALYSIS_APPROVE')")
    public AnalysisResultResponse review(@PathVariable UUID jobId,
                                         @Valid @RequestBody AnalysisReviewRequest request,
                                         @AuthenticationPrincipal Jwt jwt) {
        return results.review(jobId, UUID.fromString(jwt.getSubject()), request);
    }
}
