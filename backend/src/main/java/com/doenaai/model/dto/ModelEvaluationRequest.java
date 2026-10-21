package com.doenaai.model.dto;

import java.math.BigDecimal;
import java.util.Map;

/** 실행 결과가 아닌 사용자의 수동 평가 기록이다. */
public record ModelEvaluationRequest(String status, BigDecimal overallScore, String notes,
        Map<String, Object> metrics) {}
