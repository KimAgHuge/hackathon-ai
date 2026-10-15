package com.doenaai.analysis.service;

import java.util.Map;

/** 위험도 점수·등급과 근거별 점수가 같은 평가 기준을 따르는지 확인한다. */
final class RiskScorePolicy {
    private static final Map<String, Integer> FACTOR_LIMITS = Map.of(
            "changeScope", 25,
            "dataImpact", 20,
            "securityImpact", 25,
            "deliveryComplexity", 20,
            "uncertainty", 10
    );

    private RiskScorePolicy() {}

    static boolean matches(String level, int score, Object assessment) {
        if (score < 0 || score > 100 || !"UNKNOWN".equals(level) && !levelFor(score).equals(level)) return false;
        if (assessment == null) return true;
        if (!(assessment instanceof Map<?, ?> factors) || !factors.keySet().equals(FACTOR_LIMITS.keySet())) return false;
        int total = 0;
        for (var limit : FACTOR_LIMITS.entrySet()) {
            if (!(factors.get(limit.getKey()) instanceof Map<?, ?> factor)
                    || !factor.keySet().equals(java.util.Set.of("score", "reason"))
                    || !(factor.get("score") instanceof Number number)
                    || !Double.isFinite(number.doubleValue())
                    || number.doubleValue() != Math.rint(number.doubleValue())
                    || number.intValue() < 0 || number.intValue() > limit.getValue()
                    || !(factor.get("reason") instanceof String reason) || reason.isBlank()) return false;
            total += number.intValue();
        }
        return total == score;
    }

    static String levelFor(int score) {
        if (score >= 75) return "CRITICAL";
        if (score >= 50) return "HIGH";
        if (score >= 25) return "MEDIUM";
        return "LOW";
    }
}
