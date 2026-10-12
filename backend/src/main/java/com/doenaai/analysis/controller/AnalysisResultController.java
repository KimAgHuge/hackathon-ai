package com.doenaai.analysis.controller;

import com.doenaai.analysis.dto.AnalysisResultResponse;
import com.doenaai.analysis.service.AnalysisResultService;
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

    @GetMapping("/api/analysis-jobs/{jobId}/result")
    @PreAuthorize("@permissionChecker.has(authentication, 'ANALYSIS_READ')")
    public AnalysisResultResponse get(@PathVariable UUID jobId, @AuthenticationPrincipal Jwt jwt) {
        return results.get(jobId, UUID.fromString(jwt.getSubject()));
    }
}
