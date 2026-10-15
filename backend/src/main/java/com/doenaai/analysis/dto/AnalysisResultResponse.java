package com.doenaai.analysis.dto;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record AnalysisResultResponse(UUID jobId, UUID projectId, String summary,
                                     String riskLevel, int riskScore,
                                     Map<String, Object> result, Map<String, Object> facts,
                                     OffsetDateTime createdAt, String reviewStatus,
                                     Map<String, Object> correctedResult, String reviewNote,
                                     UUID reviewedBy, OffsetDateTime reviewedAt) {
    public AnalysisResultResponse(UUID jobId, UUID projectId, String summary,
                                  String riskLevel, int riskScore,
                                  Map<String, Object> result, Map<String, Object> facts,
                                  OffsetDateTime createdAt) {
        this(jobId, projectId, summary, riskLevel, riskScore, result, facts,
                createdAt, "PENDING", null, null, null, null);
    }
}
