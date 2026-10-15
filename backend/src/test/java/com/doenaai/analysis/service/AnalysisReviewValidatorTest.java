package com.doenaai.analysis.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisReviewValidatorTest {
    private final AnalysisReviewValidator validator = new AnalysisReviewValidator(JsonMapper.builder().build());

    @Test void acceptsACompleteHumanCorrection() {
        assertThatCode(() -> validator.validate(validResult())).doesNotThrowAnyException();
    }

    @Test void rejectsMissingFieldsAndSecrets() {
        var missing = validResult();
        missing.remove("plainLanguageSummary");
        assertThatThrownBy(() -> validator.validate(missing)).isInstanceOf(ResponseStatusException.class);

        var secret = validResult();
        secret.put("evidences", List.of(Map.of("password", "비밀")));
        assertThatThrownBy(() -> validator.validate(secret)).isInstanceOf(ResponseStatusException.class);
    }

    @Test void rejectsUnconfirmedDbChangesAndInvalidRiskScore() {
        var result = validResult();
        result.put("riskScore", 101);
        assertThatThrownBy(() -> validator.validate(result)).isInstanceOf(ResponseStatusException.class);

        result.put("riskScore", 30);
        result.put("databaseImpact", Map.of("status", "NEEDS_CLARIFICATION", "explanation", "확인이 필요합니다.",
                "observedTables", List.of(), "proposedChanges", List.of(Map.of("targetTable", "public.users",
                        "targetColumn", "name", "changeType", "ADD_COLUMN", "description", "변경",
                        "rationale", "이유")), "conditionalPaths", List.of(), "openQuestions", List.of()));
        assertThatThrownBy(() -> validator.validate(result)).isInstanceOf(ResponseStatusException.class);
    }

    @Test void rejectsContradictoryRiskAndAcceptsMatchingFactors() {
        var contradictory = validResult();
        contradictory.put("riskLevel", "HIGH");
        assertThatThrownBy(() -> validator.validate(contradictory)).isInstanceOf(ResponseStatusException.class);

        var withFactors = validResult();
        withFactors.put("riskAssessment", Map.of(
                "changeScope", Map.of("score", 10, "reason", "여러 화면을 수정합니다."),
                "dataImpact", Map.of("score", 8, "reason", "저장 데이터에 영향이 있습니다."),
                "securityImpact", Map.of("score", 0, "reason", "권한 변경은 없습니다."),
                "deliveryComplexity", Map.of("score", 7, "reason", "배포 확인이 필요합니다."),
                "uncertainty", Map.of("score", 5, "reason", "선택할 조건이 남았습니다.")));
        assertThatCode(() -> validator.validate(withFactors)).doesNotThrowAnyException();
        withFactors.put("riskScore", 31);
        assertThatThrownBy(() -> validator.validate(withFactors)).isInstanceOf(ResponseStatusException.class);
    }

    private Map<String, Object> validResult() {
        var result = new LinkedHashMap<String, Object>();
        result.put("summary", "근거를 확인한 수정 결과입니다.");
        result.put("plainLanguageSummary", "변경 전에 영향을 확인해야 합니다.");
        result.put("frameworks", List.of("Spring Boot"));
        result.put("riskLevel", "MEDIUM");
        result.put("riskScore", 30);
        result.put("impactAreas", List.of("회원 기능"));
        result.put("evidences", List.of(Map.of("file", "src/App.java", "line", 10)));
        result.put("recommendations", List.of("기존 흐름을 확인합니다."));
        result.put("databaseImpact", Map.of("status", "NO_CHANGE", "explanation", "DB 변경은 필요하지 않습니다.",
                "observedTables", List.of(), "proposedChanges", List.of(), "conditionalPaths", List.of(), "openQuestions", List.of()));
        return result;
    }
}
