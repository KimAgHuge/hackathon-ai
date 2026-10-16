package com.doenaai.analysis.service;

import com.doenaai.analysis.dto.AnalysisJobResponse;
import com.doenaai.analysis.dto.AnalysisResultResponse;
import com.doenaai.analysis.dto.TrainingCaseDraftResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 저장된 최종 결과를 검토가 필요한 학습 사례 후보로만 변환한다. */
final class TrainingCaseDraftBuilder {
    private static final List<String> GENERAL_FIELDS = List.of(
            "summary", "plainLanguageSummary", "frameworks", "riskLevel", "riskScore",
            "riskAssessment", "impactAreas", "evidences", "recommendations");

    private TrainingCaseDraftBuilder() {}

    /** 누락된 단계 판단과 비식별화 여부를 임의로 확정하지 않는다. */
    static TrainingCaseDraftResponse build(AnalysisJobResponse job, AnalysisResultResponse result) {
        Map<String, Object> effective = result.correctedResult() == null
                ? result.result() : result.correctedResult();
        Map<String, Object> facts = result.facts() == null ? Map.of() : result.facts();
        var reviewRequired = new ArrayList<String>();

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
        var databaseCapturedAt = database instanceof Map<?, ?> databaseMap ? databaseMap.get("capturedAt") : null;
        snapshot.put("databaseCapturedAt", databaseCapturedAt instanceof String ? databaseCapturedAt : null);
        if (!(databaseCapturedAt instanceof String)) {
            reviewRequired.add("snapshot.databaseCapturedAt: 이전 분석에 DB 구조 수집 시각이 없어 확인이 필요합니다.");
        }

        var approval = new LinkedHashMap<String, Object>();
        approval.put("reviewerId", null);
        approval.put("approvedAt", result.reviewedAt() == null ? null : result.reviewedAt().toString());
        approval.put("note", result.reviewNote());
        reviewRequired.add("approval: 비식별 검토자 ID와 승인 이유·시각을 다시 확인해야 합니다.");

        var general = new LinkedHashMap<String, Object>();
        for (String field : GENERAL_FIELDS) {
            general.put(field, effective.get(field));
            if (effective.get(field) == null) {
                reviewRequired.add("answers.general." + field + ": 저장된 결과에 없어 작성이 필요합니다.");
            }
        }
        general.put("databaseRelevance", null);
        general.put("databaseRelevanceReason", null);
        reviewRequired.add("answers.general.databaseRelevance: 최종 결과에 원래 단계 판단이 없어 사람이 결정해야 합니다.");
        reviewRequired.add("answers.general.databaseRelevanceReason: 실제 근거에 따른 판단 이유를 작성해야 합니다.");
        reviewRequired.add("answers.general.riskAssessment: 최종 위험도는 DB 단계 후 조정됐을 수 있어 재검토해야 합니다.");

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
        draft.put("projectGroup", null);
        draft.put("humanApproved", false);
        draft.put("sanitized", false);
        draft.put("approval", approval);
        draft.put("snapshot", snapshot);
        draft.put("request", request);
        draft.put("answers", answers);
        reviewRequired.add("projectGroup: 동일 프로젝트 사례에 사용할 비식별 그룹명을 지정해야 합니다.");
        reviewRequired.add("humanApproved, sanitized: 학습 정답 검토와 비식별화 후에만 true로 바꿔야 합니다.");
        return new TrainingCaseDraftResponse(draft, List.copyOf(reviewRequired));
    }
}
