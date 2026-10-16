package com.doenaai.analysis.dto;

import java.util.List;
import java.util.Map;

/** 학습 사례 후보와 사람이 채우거나 검토해야 할 항목을 함께 전달한다. */
public record TrainingCaseDraftResponse(Map<String, Object> caseDraft, List<String> reviewRequired) {}
