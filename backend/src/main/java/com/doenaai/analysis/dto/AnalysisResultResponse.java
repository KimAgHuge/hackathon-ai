package com.doenaai.analysis.dto;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record AnalysisResultResponse(UUID jobId, UUID projectId, String summary,
                                     String riskLevel, int riskScore,
                                     Map<String, Object> result, Map<String, Object> facts,
                                     OffsetDateTime createdAt) {
}
