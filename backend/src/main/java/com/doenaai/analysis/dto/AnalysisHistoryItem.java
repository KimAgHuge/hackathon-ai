package com.doenaai.analysis.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 저장된 작업의 목록 표시용 요약. 원본 JSON은 개별 결과 API에서만 조회한다. */
public record AnalysisHistoryItem(
        UUID jobId,
        UUID projectId,
        String projectName,
        UUID requirementVersionId,
        String requirementTitle,
        String status,
        String riskLevel,
        String summary,
        boolean approved,
        UUID evidenceReusedFromJobId,
        boolean reusableEvidence,
        String sourceCommit,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt
) {}
