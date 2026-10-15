package com.doenaai.analysis.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class AnalysisReviewValidator {
    private static final Set<String> RESULT_FIELDS = Set.of(
            "summary", "plainLanguageSummary", "frameworks", "riskLevel", "riskScore",
            "impactAreas", "evidences", "recommendations", "databaseImpact");
    private static final Set<String> RISK_LEVELS = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL", "UNKNOWN");
    private static final Set<String> DATABASE_STATUSES = Set.of(
            "CHANGE_REQUIRED", "NO_CHANGE", "NEEDS_CLARIFICATION", "UNKNOWN", "NOT_APPLICABLE");
    private static final Set<String> CHANGE_TYPES = Set.of(
            "CREATE_TABLE", "ADD_COLUMN", "ALTER_COLUMN", "DROP_COLUMN",
            "ADD_CONSTRAINT", "ALTER_CONSTRAINT", "DATA_MIGRATION", "OTHER");
    private static final Set<String> SECRET_KEYS = Set.of(
            "password", "dbpassword", "token", "accesstoken", "apikey", "authorization",
            "secret", "privatekey", "credential", "ciphertext", "passphrase");
    private final ObjectMapper objectMapper;

    /** 수정본의 필수 형식과 크기를 확인한다. 근거의 사실 여부는 승인자가 확인해야 한다. */
    public void validate(Map<String, Object> result) {
        if (result == null || !result.keySet().equals(RESULT_FIELDS)
                && !result.keySet().equals(withRiskAssessment())) {
            throw invalid("수정 결과의 필수 항목이 일치하지 않습니다.");
        }
        if (objectMapper.writeValueAsString(result).length() > 200_000 || hasSecretKey(result, 0)) {
            throw invalid("수정 결과가 너무 크거나 비밀정보 필드가 포함돼 있습니다.");
        }
        requireText(result.get("summary"), 10_000);
        String plain = requireText(result.get("plainLanguageSummary"), 200);
        if (plain.contains("\n") || plain.contains("\r")) throw invalid("쉬운 요약은 한 줄로 입력해 주세요.");
        requireStringList(result.get("frameworks"));
        requireStringList(result.get("impactAreas"));
        requireStringList(result.get("recommendations"));
        List<?> evidences = requireList(result.get("evidences"));
        if (evidences.stream().anyMatch(item -> !(item instanceof Map<?, ?>))) {
            throw invalid("분석 근거 형식이 올바르지 않습니다.");
        }
        if (!(result.get("riskLevel") instanceof String risk) || !RISK_LEVELS.contains(risk)) {
            throw invalid("위험도 구분이 올바르지 않습니다.");
        }
        if (!(result.get("riskScore") instanceof Number score) ||
                !Double.isFinite(score.doubleValue()) || score.doubleValue() != Math.rint(score.doubleValue()) ||
                score.intValue() < 0 || score.intValue() > 100) {
            throw invalid("위험도 점수는 0부터 100까지의 정수여야 합니다.");
        }
        if (!RiskScorePolicy.matches(risk, score.intValue(), result.get("riskAssessment"))) {
            throw invalid("위험도 등급·점수·평가 근거가 일치하지 않습니다.");
        }
        validateDatabaseImpact(result.get("databaseImpact"));
    }

    private Set<String> withRiskAssessment() {
        var fields = new java.util.HashSet<>(RESULT_FIELDS);
        fields.add("riskAssessment");
        return fields;
    }

    private void validateDatabaseImpact(Object value) {
        if (!(value instanceof Map<?, ?> impact) || !impact.keySet().equals(Set.of(
                "status", "explanation", "observedTables", "proposedChanges", "conditionalPaths", "openQuestions"))) {
            throw invalid("DB 영향 판단 항목이 올바르지 않습니다.");
        }
        if (!(impact.get("status") instanceof String status) || !DATABASE_STATUSES.contains(status)) {
            throw invalid("DB 영향 판단 상태가 올바르지 않습니다.");
        }
        requireText(impact.get("explanation"), 10_000);
        requireStringList(impact.get("observedTables"));
        requireStringList(impact.get("openQuestions"));
        List<?> changes = requireList(impact.get("proposedChanges"));
        List<?> paths = requireList(impact.get("conditionalPaths"));
        for (Object change : changes) {
            if (!(change instanceof Map<?, ?> item)) throw invalid("DB 변경안 형식이 올바르지 않습니다.");
            requireText(item.get("targetTable"), 500);
            if (item.get("targetColumn") != null) requireText(item.get("targetColumn"), 500);
            if (!(item.get("changeType") instanceof String type) || !CHANGE_TYPES.contains(type)) {
                throw invalid("DB 변경 유형이 올바르지 않습니다.");
            }
            if (Set.of("ADD_COLUMN", "ALTER_COLUMN", "DROP_COLUMN").contains(type) && item.get("targetColumn") == null) {
                throw invalid("컬럼 변경에는 대상 컬럼이 필요합니다.");
            }
            requireText(item.get("description"), 10_000);
            requireText(item.get("rationale"), 10_000);
        }
        for (Object path : paths) {
            if (!(path instanceof Map<?, ?> item)) throw invalid("조건별 DB 영향 형식이 올바르지 않습니다.");
            requireText(item.get("condition"), 10_000);
            requireText(item.get("databaseEffect"), 10_000);
        }
        if ("CHANGE_REQUIRED".equals(status) && changes.isEmpty()) throw invalid("DB 변경 필요 판단에는 변경안이 필요합니다.");
        if (!"CHANGE_REQUIRED".equals(status) && !changes.isEmpty()) throw invalid("확정되지 않은 DB 변경안을 승인할 수 없습니다.");
        if ("NEEDS_CLARIFICATION".equals(status) &&
                (paths.size() < 2 || requireList(impact.get("openQuestions")).isEmpty())) {
            throw invalid("확인 필요 판단에는 조건별 경로와 확인 질문이 필요합니다.");
        }
    }

    private String requireText(Object value, int maxLength) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > maxLength) {
            throw invalid("수정 결과의 문장 항목이 비었거나 너무 깁니다.");
        }
        return text.trim();
    }

    private void requireStringList(Object value) {
        List<?> items = requireList(value);
        for (Object item : items) requireText(item, 10_000);
    }

    private List<?> requireList(Object value) {
        if (!(value instanceof List<?> items)) throw invalid("수정 결과의 목록 형식이 올바르지 않습니다.");
        return items;
    }

    private boolean hasSecretKey(Object value, int depth) {
        if (depth > 20) return true;
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase().replaceAll("[^a-z0-9]", "");
                if (SECRET_KEYS.contains(key) || hasSecretKey(entry.getValue(), depth + 1)) return true;
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) if (hasSecretKey(item, depth + 1)) return true;
        }
        return false;
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
