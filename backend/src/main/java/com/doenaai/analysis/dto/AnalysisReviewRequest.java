package com.doenaai.analysis.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** 검토 결정과 이유, 승인할 때만 선택적으로 입력하는 전체 수정 결과. */
public record AnalysisReviewRequest(
        @NotNull AnalysisReviewDecision decision,
        @NotBlank @Size(max = 4000) String note,
        Map<String, Object> correctedResult
) {}
