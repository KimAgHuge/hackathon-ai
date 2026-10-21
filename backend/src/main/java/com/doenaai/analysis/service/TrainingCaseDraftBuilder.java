package com.doenaai.analysis.service;

import com.doenaai.analysis.dto.AnalysisJobResponse;
import com.doenaai.analysis.dto.AnalysisResultResponse;
import com.doenaai.analysis.dto.TrainingCaseDraftResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 저장된 최종 결과를 검토가 필요한 학습 사례 후보로만 변환한다. */
final class TrainingCaseDraftBuilder {
    private static final List<String> GENERAL_FIELDS = List.of(
            "summary", "plainLanguageSummary", "frameworks", "riskLevel", "riskScore",
            "riskAssessment", "impactAreas", "evidences", "recommendations");

    private TrainingCaseDraftBuilder() {}

    /** 저장된 값과 수집 완료 기록에서 초안을 보완하고 자동 보완의 출처를 함께 알린다. */
    static TrainingCaseDraftResponse build(AnalysisJobResponse job, AnalysisResultResponse result, UUID requestingUserId) {
        Map<String, Object> effective = result.correctedResult() == null
                ? result.result() : result.correctedResult();
        Map<String, Object> facts = result.facts() == null ? Map.of() : result.facts();
        var reviewRequired = new ArrayList<String>();
        var autoFilled = new ArrayList<String>();

        var source = facts.get("sourceSummary");
        var database = facts.get("databaseSummary");
        var relations = facts.get("relations");
        if (!(source instanceof Map<?, ?>)) reviewRequired.add("request.sourceSummary: 실제 AI 입력 근거가 없습니다.");
        if (!(database instanceof Map<?, ?>)) reviewRequired.add("request.databaseSummary: 실제 DB 입력 근거가 없습니다.");
        if (!(relations instanceof List<?>)) reviewRequired.add("request.relations: 실제 관계 입력 근거가 없습니다.");

        var request = new LinkedHashMap<String, Object>();
        request.put("projectId", result.projectId().toString());
        request.put("sourceSummary", source);
        request.put("databaseSummary", database);
        request.put("relations", relations);
        reviewRequired.add("request: 개인정보·비밀값·고객 정보를 검토하고 비식별화해야 합니다.");

        var sourceRevision = source instanceof Map<?, ?> sourceMap ? sourceMap.get("commitSha") : null;
        if (!(sourceRevision instanceof String sha) || !sha.matches("[0-9a-f]{40}")) {
            sourceRevision = null;
            reviewRequired.add("snapshot.sourceRevision: 실제 40자리 Git 커밋을 확인해야 합니다.");
        }

        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("sourceRevision", sourceRevision);
        snapshot.put("requirementVersionId", job.requirementVersionId().toString());
        var databaseCapturedAt = databaseCapturedAt(job, database, autoFilled);
        snapshot.put("databaseCapturedAt", databaseCapturedAt);
        if (databaseCapturedAt == null) {
            reviewRequired.add("snapshot.databaseCapturedAt: 저장된 DB 수집 시각과 같은 작업의 수집 완료 기록이 없습니다. 당시 시각을 확인하거나 새로 분석해 주세요. 현재 시각으로 대신 채우지 않습니다.");
        }

        var approval = new LinkedHashMap<String, Object>();
        approval.put("reviewerId", pseudonym("reviewer", requestingUserId));
        approval.put("approvedAt", result.reviewedAt() == null ? null : result.reviewedAt().toString());
        approval.put("note", result.reviewNote());
        if (result.reviewedAt() == null) reviewRequired.add("approval.approvedAt: 승인 시각을 확인해야 합니다.");
        if (result.reviewNote() == null || result.reviewNote().isBlank()) {
            reviewRequired.add("approval.note: 승인 이유를 작성해야 합니다.");
        }

        var general = new LinkedHashMap<String, Object>();
        var savedGeneral = result.correctedResult() == null && facts.get("generalAnalysis") instanceof Map<?, ?> saved
                ? saved : Map.of();
        var generalSource = savedGeneral.isEmpty() ? effective : savedGeneral;
        for (String field : GENERAL_FIELDS) {
            general.put(field, generalSource.get(field));
            if (generalSource.get(field) == null) {
                reviewRequired.add("answers.general." + field + ": 저장된 결과에 없어 작성이 필요합니다.");
            }
        }
        fillDatabaseRelevance(general, savedGeneral, effective, autoFilled);
        if (savedGeneral.get("databaseReviewPlan") instanceof Map<?, ?> plan) {
            general.put("databaseReviewPlan", plan);
        }
        // DB 결론을 바꾸지 않은 문장 수정본은 원래 단계의 관련 없음 판단을 검토용 후보로 재사용한다.
        if (general.get("databaseRelevance") == null && result.correctedResult() != null
                && facts.get("generalAnalysis") instanceof Map<?, ?> originalGeneral
                && "UNRELATED".equals(originalGeneral.get("databaseRelevance"))
                && originalGeneral.get("databaseRelevanceReason") instanceof String originalReason && !originalReason.isBlank()
                && effective.get("databaseImpact") instanceof Map<?, ?> correctedImpact
                && "NOT_APPLICABLE".equals(correctedImpact.get("status"))
                && java.util.Objects.equals(correctedImpact, result.result().get("databaseImpact"))) {
            general.put("databaseRelevance", "UNRELATED");
            general.put("databaseRelevanceReason", originalReason);
            autoFilled.add("answers.general.databaseRelevance: DB 판단이 바뀌지 않은 수정본이므로 원래 일반 분석의 관련 없음 판단을 검토용 후보로 복원했습니다. 수정한 정답은 보존하며 전송 전 확인이 필요합니다.");
        }
        if (general.get("databaseRelevance") == null) {
            reviewRequired.add("answers.general.databaseRelevance: 관련성 판단을 복원할 수 없어 선택이 필요합니다.");
        }
        if (general.get("databaseRelevanceReason") == null) {
            reviewRequired.add("answers.general.databaseRelevanceReason: 실제 근거에 따른 판단 이유를 작성해야 합니다.");
        }
        if (savedGeneral.isEmpty()) reviewRequired.add("answers.general.riskAssessment: 최종 위험도는 DB 단계 후 조정됐을 수 있어 재검토해야 합니다.");

        var answers = new LinkedHashMap<String, Object>();
        answers.put("general", general);
        if (effective.get("databaseImpact") instanceof Map<?, ?> impact) {
            if (!"NOT_APPLICABLE".equals(impact.get("status"))) answers.put("database", impact);
        } else {
            reviewRequired.add("answers.database: 최종 DB 분석 결과가 없어 관련성을 확인해야 합니다.");
        }
        reviewRequired.add("answers: 단계별 정답과 수집 근거의 일치 여부를 검토해야 합니다.");

        var draft = new LinkedHashMap<String, Object>();
        draft.put("caseId", "analysis-" + job.jobId());
        draft.put("projectGroup", pseudonym("project", result.projectId()));
        draft.put("humanApproved", false);
        draft.put("sanitized", false);
        draft.put("approval", approval);
        draft.put("snapshot", snapshot);
        draft.put("request", request);
        draft.put("answers", answers);
        if (facts.get("analysisModel") instanceof String model) draft.put("sourceModel", model);
        else reviewRequired.add("sourceModel: 분석 생성 모델 기록이 없습니다. 모델별 학습에서는 제외하며 현재 설정으로 추정하지 않습니다.");
        reviewRequired.add("humanApproved, sanitized: 정답 검토와 비식별화가 필요합니다. 전송 화면의 확인란을 선택하면 자동으로 반영됩니다.");
        return new TrainingCaseDraftResponse(draft, List.copyOf(reviewRequired), List.copyOf(autoFilled));
    }

    /** DB 수집 자체의 시각이 없을 때만 같은 작업의 단일 수집 완료 기록을 사용한다. */
    private static String databaseCapturedAt(AnalysisJobResponse job, Object database, List<String> autoFilled) {
        if (!(database instanceof Map<?, ?> summary)) return null;
        if (validTimestamp(summary.get("capturedAt"))) {
            autoFilled.add("snapshot.databaseCapturedAt: 저장된 DB 구조의 수집 시각을 사용했습니다.");
            return (String) summary.get("capturedAt");
        }
        if (job.activityLog() == null) return null;
        var completed = job.activityLog().stream()
                .filter(event -> "데이터베이스 구조 수집".equals(event.label()) && "COMPLETED".equals(event.state()))
                .filter(event -> validTimestamp(event.at()))
                .filter(event -> job.createdAt() != null && !OffsetDateTime.parse(event.at()).isBefore(job.createdAt()))
                .filter(event -> job.completedAt() != null && !OffsetDateTime.parse(event.at()).isAfter(job.completedAt()))
                .map(event -> OffsetDateTime.parse(event.at()).toInstant()).distinct().toList();
        if (completed.size() != 1) return null;
        autoFilled.add("snapshot.databaseCapturedAt: 같은 분석 작업의 DB 구조 수집 완료 기록에서 복원했습니다. 원래 구조에 저장된 시각과는 구분해 검토해 주세요.");
        return completed.getFirst().toString();
    }

    /** 원래 단계 응답을 우선 사용하고 예전 결과는 DB 상태에 따른 검토용 후보만 만든다. */
    private static void fillDatabaseRelevance(Map<String, Object> general, Map<?, ?> savedGeneral,
                                               Map<String, Object> effective, List<String> autoFilled) {
        general.put("databaseRelevance", null);
        general.put("databaseRelevanceReason", null);
        if (savedGeneral.get("databaseRelevance") instanceof String savedRelevance
                && List.of("RELATED", "UNRELATED", "UNCERTAIN").contains(savedRelevance)
                && savedGeneral.get("databaseRelevanceReason") instanceof String reason && !reason.isBlank()) {
            general.put("databaseRelevance", savedGeneral.get("databaseRelevance"));
            general.put("databaseRelevanceReason", reason);
            autoFilled.add("answers.general: 저장된 일반 분석 단계 응답을 복원했습니다. 최종 DB 분석 결과와 구분해 검토해 주세요.");
            return;
        }
        if (!(effective.get("databaseImpact") instanceof Map<?, ?> impact)) return;
        String relevance = switch (String.valueOf(impact.get("status"))) {
            case "CHANGE_REQUIRED", "NO_CHANGE" -> "RELATED";
            case "NEEDS_CLARIFICATION", "UNKNOWN" -> "UNCERTAIN";
            default -> null;
        };
        if (relevance == null) return;
        general.put("databaseRelevance", relevance);
        if (impact.get("explanation") instanceof String explanation && !explanation.isBlank()) {
            general.put("databaseRelevanceReason", explanation);
        }
        autoFilled.add("answers.general.databaseRelevance: 승인된 DB 영향 판단에서 검토용 관련성 후보를 만들었습니다. 원래 모델의 일반 분석 판단을 복원한 값은 아니므로 확인 후 승인해 주세요.");
    }

    private static boolean validTimestamp(Object value) {
        if (!(value instanceof String text)) return false;
        try {
            OffsetDateTime.parse(text);
            return true;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    /** 같은 프로젝트와 로그인 사용자는 모든 사례에서 같은 비식별 식별자를 사용한다. */
    private static String pseudonym(String kind, UUID id) {
        try {
            var value = ("doenaai-training:" + kind + ":" + id).getBytes(StandardCharsets.UTF_8);
            return kind + "-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("학습 사례 식별자를 생성할 수 없습니다.", exception);
        }
    }
}
