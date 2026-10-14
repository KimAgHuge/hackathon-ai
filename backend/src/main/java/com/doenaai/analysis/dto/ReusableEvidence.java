package com.doenaai.analysis.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 완료된 작업에서 모델에 실제 전달했던 소스·DB 근거. */
public record ReusableEvidence(UUID sourceJobId, Map<String, Object> sourceSummary,
                               Map<String, Object> databaseSummary, List<Map<String, Object>> relations) {}
