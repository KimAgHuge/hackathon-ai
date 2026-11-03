package com.doenaai.analysis.service;

import com.doenaai.analysis.dto.AnalysisJobResponse;
import com.doenaai.analysis.dto.AnalysisResultResponse;
import com.doenaai.analysis.dto.AnalysisActivityEvent;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TrainingCaseDraftBuilderTest {
    private final UUID jobId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID requirementVersionId = UUID.randomUUID();
    private final UUID reviewerId = UUID.randomUUID();
    private final OffsetDateTime now = OffsetDateTime.parse("2026-10-16T10:00:00+09:00");

    @Test void keepsUnstoredJudgmentsEmptyAndPrefersApprovedCorrection() {
        var job = AnalysisJobResponse.of(jobId, projectId, requirementVersionId,
                "COMPLETED", 100, 100, 100, now);
        var original = Map.<String, Object>of("summary", "AI 원본", "databaseImpact", Map.of("status", "NO_CHANGE"));
        var corrected = Map.<String, Object>of("summary", "사람이 수정한 결론", "riskScore", 45,
                "databaseImpact", Map.of("status", "NEEDS_CLARIFICATION", "openQuestions", List.of("대상 확인")));
        var facts = Map.<String, Object>of("sourceSummary", Map.of("commitSha", "a".repeat(40)),
                "databaseSummary", Map.of("type", "POSTGRESQL", "capturedAt", "2026-10-16T00:30:00Z"), "relations", List.of());
        var result = new AnalysisResultResponse(jobId, projectId, "사람이 수정한 결론", "MEDIUM", 45,
                original, facts, now, "APPROVED", corrected, "근거를 검토했습니다.", reviewerId, now);

        var response = TrainingCaseDraftBuilder.build(job, result, reviewerId);
        var draft = response.caseDraft();
        var answers = (Map<?, ?>) draft.get("answers");
        var general = (Map<?, ?>) answers.get("general");
        var snapshot = (Map<?, ?>) draft.get("snapshot");
        var approval = (Map<?, ?>) draft.get("approval");

        assertThat(general.get("summary")).isEqualTo("사람이 수정한 결론");
        assertThat(general.get("databaseRelevance")).isEqualTo("UNCERTAIN");
        assertThat(general.get("databaseRelevanceReason")).isNull();
        assertThat(answers.get("database")).isEqualTo(corrected.get("databaseImpact"));
        assertThat(snapshot.get("sourceRevision")).isEqualTo("a".repeat(40));
        assertThat(snapshot.get("databaseCapturedAt")).isEqualTo("2026-10-16T00:30:00Z");
        assertThat(approval.get("reviewerId").toString()).matches("reviewer-[0-9a-f]{64}")
                .doesNotContain(reviewerId.toString());
        assertThat(approval.get("approvedAt")).isEqualTo(now.toString());
        assertThat(draft.get("humanApproved")).isEqualTo(false);
        assertThat(draft.get("sanitized")).isEqualTo(false);
        assertThat(draft.get("projectGroup").toString()).matches("project-[0-9a-f]{64}")
                .doesNotContain(projectId.toString());
        assertThat(response.reviewRequired()).noneMatch(item -> item.startsWith("projectGroup:") || item.startsWith("approval:"));
        assertThat(response.autoFilled()).anyMatch(item -> item.contains("검토용 관련성 후보"));

        var otherJob = AnalysisJobResponse.of(UUID.randomUUID(), projectId, UUID.randomUUID(),
                "COMPLETED", 100, 100, 100, now);
        var repeated = TrainingCaseDraftBuilder.build(otherJob, result, reviewerId).caseDraft();
        assertThat(repeated.get("projectGroup")).isEqualTo(draft.get("projectGroup"));
        assertThat(((Map<?, ?>) repeated.get("approval")).get("reviewerId")).isEqualTo(approval.get("reviewerId"));
        var otherReviewer = TrainingCaseDraftBuilder.build(otherJob, result, UUID.randomUUID()).caseDraft();
        assertThat(otherReviewer.get("projectGroup")).isEqualTo(draft.get("projectGroup"));
        assertThat(((Map<?, ?>) otherReviewer.get("approval")).get("reviewerId")).isNotEqualTo(approval.get("reviewerId"));

        var otherProject = new AnalysisResultResponse(jobId, UUID.randomUUID(), "요약", "LOW", 10,
                original, facts, now, "APPROVED", null, "검토", reviewerId, now);
        assertThat(TrainingCaseDraftBuilder.build(job, otherProject, reviewerId).caseDraft().get("projectGroup"))
                .isNotEqualTo(draft.get("projectGroup"));
    }

    @Test void doesNotInventMissingFactsOrDatabaseAnswer() {
        var job = AnalysisJobResponse.of(jobId, projectId, requirementVersionId,
                "COMPLETED", 100, 100, 100, now);
        var result = new AnalysisResultResponse(jobId, projectId, "요약", "UNKNOWN", 0,
                Map.of("summary", "요약", "databaseImpact", Map.of("status", "NOT_APPLICABLE")),
                Map.of(), now, "APPROVED", null, "검토", null, now);

        var response = TrainingCaseDraftBuilder.build(job, result, reviewerId);
        var draft = response.caseDraft();
        var request = (Map<?, ?>) draft.get("request");
        var snapshot = (Map<?, ?>) draft.get("snapshot");
        var answers = (Map<?, ?>) draft.get("answers");

        assertThat(request.get("sourceSummary")).isNull();
        assertThat(snapshot.get("sourceRevision")).isNull();
        assertThat(answers.containsKey("database")).isFalse();
        assertThat(((Map<?, ?>) draft.get("approval")).get("reviewerId").toString()).matches("reviewer-[0-9a-f]{64}");
        assertThat(response.reviewRequired()).anyMatch(item -> item.startsWith("request.sourceSummary:"));
        assertThat(snapshot.get("databaseCapturedAt")).isNull();
        assertThat(((Map<?, ?>) answers.get("general")).get("databaseRelevance")).isNull();
    }

    @Test void trainingRequestKeepsOnlyRecordedBooleanThinkingChoices() {
        var original = legacyResult("NO_CHANGE");
        var job = jobWithActivity(List.of());
        var legacyDraft = TrainingCaseDraftBuilder.build(job, original, reviewerId).caseDraft();
        assertThat(((Map<?, ?>) legacyDraft.get("request")).containsKey("thinkingEnabled")).isFalse();
        for (Boolean enabled : new Boolean[]{true, false, null}) {
            var facts = new java.util.LinkedHashMap<>(original.facts());
            facts.put("thinkingEnabled", enabled);
            var result = new AnalysisResultResponse(jobId, projectId, original.summary(), original.riskLevel(),
                    original.riskScore(), original.result(), facts, now, "APPROVED", null, "검토", reviewerId, now);

            var draft = TrainingCaseDraftBuilder.build(job, result, reviewerId).caseDraft();
            var request = (Map<?, ?>) draft.get("request");

            if (enabled == null) assertThat(request.containsKey("thinkingEnabled")).isFalse();
            else assertThat(request.get("thinkingEnabled")).isEqualTo(enabled);
        }
    }

    @Test void trainingRequestKeepsOnlyRecordedValidThinkingEffort() {
        var original = legacyResult("NO_CHANGE");
        var job = jobWithActivity(List.of());
        var legacy = TrainingCaseDraftBuilder.build(job, original, reviewerId).caseDraft();
        assertThat(((Map<?, ?>) legacy.get("request")).containsKey("thinkingEffort")).isFalse();
        for (Object effort : new Object[]{"low", "medium", "high", null, "maximum", 42}) {
            var facts = new java.util.LinkedHashMap<>(original.facts());
            facts.put("thinkingEnabled", false);
            facts.put("thinkingEffort", effort);
            var result = new AnalysisResultResponse(jobId, projectId, original.summary(), original.riskLevel(),
                    original.riskScore(), original.result(), facts, now, "APPROVED", null, "검토", reviewerId, now);

            var request = (Map<?, ?>) TrainingCaseDraftBuilder.build(job, result, reviewerId).caseDraft().get("request");

            assertThat(request.get("thinkingEnabled")).isEqualTo(false);
            if (effort instanceof String value && List.of("low", "medium", "high").contains(value)) {
                assertThat(request.get("thinkingEffort")).isEqualTo(value);
            } else assertThat(request.containsKey("thinkingEffort")).isFalse();
        }
    }

    @Test void restoresDatabaseCollectionTimeOnlyFromMatchingCompletedActivity() {
        var events = List.of(new AnalysisActivityEvent("데이터베이스 구조 수집", "STARTED", now.toString()),
                new AnalysisActivityEvent("데이터베이스 구조 수집", "COMPLETED", now.plusSeconds(3).toString()),
                new AnalysisActivityEvent("분석 결과 저장", "COMPLETED", now.plusMinutes(5).toString()));
        var response = TrainingCaseDraftBuilder.build(jobWithActivity(events), legacyResult("CHANGE_REQUIRED"), reviewerId);

        assertThat(((Map<?, ?>) response.caseDraft().get("snapshot")).get("databaseCapturedAt"))
                .isEqualTo(now.plusSeconds(3).toInstant().toString());
        assertThat(response.autoFilled()).anyMatch(item -> item.contains("수집 완료 기록에서 복원"));
        var general = (Map<?, ?>) ((Map<?, ?>) response.caseDraft().get("answers")).get("general");
        assertThat(general.get("databaseRelevance")).isEqualTo("RELATED");
        assertThat(general.get("databaseRelevanceReason")).isEqualTo("저장된 DB 영향 판단 이유입니다.");
    }

    @Test void doesNotReplaceUnknownCollectionTimeWithJobOrApprovalTime() {
        for (var events : List.of(
                List.<AnalysisActivityEvent>of(),
                List.of(new AnalysisActivityEvent("데이터베이스 구조 수집", "STARTED", now.toString())),
                List.of(new AnalysisActivityEvent("선택한 이전 분석 근거 확인", "COMPLETED", now.toString())),
                List.of(new AnalysisActivityEvent("데이터베이스 구조 수집", "COMPLETED", "잘못된 시각")),
                List.of(new AnalysisActivityEvent("데이터베이스 구조 수집", "COMPLETED", now.minusSeconds(1).toString())),
                List.of(new AnalysisActivityEvent("데이터베이스 구조 수집", "COMPLETED", now.plusHours(1).toString())),
                List.of(new AnalysisActivityEvent("데이터베이스 구조 수집", "COMPLETED", now.plusSeconds(1).toString()),
                        new AnalysisActivityEvent("데이터베이스 구조 수집", "COMPLETED", now.plusSeconds(2).toString())))) {
            var response = TrainingCaseDraftBuilder.build(jobWithActivity(events), legacyResult("NO_CHANGE"), reviewerId);
            assertThat(((Map<?, ?>) response.caseDraft().get("snapshot")).get("databaseCapturedAt")).isNull();
            assertThat(response.reviewRequired()).anyMatch(item -> item.startsWith("snapshot.databaseCapturedAt:"));
        }
    }

    @Test void prefersSavedStageResponseButNeverOverridesApprovedCorrection() {
        var legacy = legacyResult("CHANGE_REQUIRED");
        var facts = new java.util.LinkedHashMap<>(legacy.facts());
        var plan = Map.of("targetTables", List.of("example.records"), "evidenceFiles", List.of("app/store.py"),
                "requiresFullReview", false, "reason", "저장된 참조를 확인했습니다.");
        facts.put("generalAnalysis", Map.of("summary", "원래 단계 요약", "riskScore", 10,
                "databaseRelevance", "UNRELATED", "databaseRelevanceReason", "원래 단계의 판단 이유", "databaseReviewPlan", plan));
        var original = new AnalysisResultResponse(jobId, projectId, "최종 요약", "LOW", 10,
                legacy.result(), facts, now, "APPROVED", null, "검토", reviewerId, now);
        var response = TrainingCaseDraftBuilder.build(jobWithActivity(List.of()), original, reviewerId);
        var general = (Map<?, ?>) ((Map<?, ?>) response.caseDraft().get("answers")).get("general");
        assertThat(general.get("summary")).isEqualTo("원래 단계 요약");
        assertThat(general.get("databaseRelevanceReason")).isEqualTo("원래 단계의 판단 이유");
        assertThat(general.get("databaseReviewPlan")).isEqualTo(plan);

        var corrected = new AnalysisResultResponse(jobId, projectId, "승인 수정 요약", "LOW", 10,
                legacy.result(), facts, now, "APPROVED", legacy.result(), "검토", reviewerId, now);
        var correctedDraft = TrainingCaseDraftBuilder.build(jobWithActivity(List.of()), corrected, reviewerId);
        var correctedGeneral = (Map<?, ?>) ((Map<?, ?>) correctedDraft.caseDraft().get("answers")).get("general");
        assertThat(correctedGeneral.get("summary")).isEqualTo("최종 요약");
        assertThat(correctedGeneral.get("databaseRelevance")).isEqualTo("RELATED");
        assertThat(correctedGeneral.containsKey("databaseReviewPlan")).isFalse();
    }

    @Test void adapterAnalysisPreservesTheOriginalTrainingFamilyAndTheExactAdapterIdentity() {
        String runtimeModel = "doenaai-mlx:web-" + UUID.randomUUID();
        String adapterDigest = "c".repeat(64);
        var original = legacyResult("NO_CHANGE");
        var facts = new java.util.LinkedHashMap<>(original.facts());
        facts.put("analysisModel", runtimeModel);
        facts.put("analysisSourceModel", "qwen3.8:27b");
        facts.put("analysisAdapterDigest", adapterDigest);
        var result = new AnalysisResultResponse(jobId, projectId, original.summary(), original.riskLevel(), original.riskScore(),
                original.result(), facts, now, "APPROVED", null, "근거를 검토했습니다.", reviewerId, now);

        var response = TrainingCaseDraftBuilder.build(jobWithActivity(List.of()), result, reviewerId);
        assertThat(response.caseDraft().get("sourceModel")).isEqualTo("qwen3.8:27b");
        assertThat(response.caseDraft().get("sourceAdapter")).isEqualTo(Map.of("model", runtimeModel, "digest", adapterDigest));
        assertThat(response.reviewRequired()).noneMatch(item -> item.startsWith("sourceModel:"));
        assertThat(result.facts().get("analysisModel")).isEqualTo(runtimeModel);
    }

    @Test void ordinaryAnalysisKeepsItsSavedModelWithoutInventingAnAdapter() {
        var original = legacyResult("NO_CHANGE");
        var facts = new java.util.LinkedHashMap<>(original.facts());
        facts.put("analysisModel", "qwen3.8:27b");
        var result = new AnalysisResultResponse(jobId, projectId, original.summary(), original.riskLevel(), original.riskScore(),
                original.result(), facts, now, "APPROVED", null, "근거를 검토했습니다.", reviewerId, now);

        var draft = TrainingCaseDraftBuilder.build(jobWithActivity(List.of()), result, reviewerId).caseDraft();
        assertThat(draft.get("sourceModel")).isEqualTo("qwen3.8:27b");
        assertThat(draft).doesNotContainKey("sourceAdapter");
        assertThat(draft).doesNotContainKey("sourceTrainedModel");
    }

    @Test void trainedModelAnalysisPreservesOriginalFamilyAndExactInstalledModelDigest() {
        String runtimeModel = "doenaai-trained:web-" + UUID.randomUUID();
        String installedDigest = "d".repeat(64);
        var original = legacyResult("NO_CHANGE");
        var facts = new java.util.LinkedHashMap<>(original.facts());
        facts.put("analysisModel", runtimeModel);
        facts.put("analysisSourceModel", "qwen3.8:27b");
        facts.put("analysisTrainedModelDigest", installedDigest);
        var result = new AnalysisResultResponse(jobId, projectId, original.summary(), original.riskLevel(), original.riskScore(),
                original.result(), facts, now, "APPROVED", null, "근거를 검토했습니다.", reviewerId, now);

        var response = TrainingCaseDraftBuilder.build(jobWithActivity(List.of()), result, reviewerId);
        assertThat(response.caseDraft()).containsEntry("sourceModel", "qwen3.8:27b")
                .containsEntry("sourceTrainedModel", Map.of("model", runtimeModel, "digest", installedDigest))
                .doesNotContainKey("sourceAdapter");
        assertThat(response.reviewRequired()).noneMatch(item -> item.startsWith("sourceModel:"));
        assertThat(result.facts().get("analysisModel")).isEqualTo(runtimeModel);
    }

    private AnalysisJobResponse jobWithActivity(List<AnalysisActivityEvent> events) {
        return new AnalysisJobResponse(jobId, projectId, requirementVersionId, "COMPLETED", 100, 100, 100, 100,
                now, null, now, now.plusMinutes(5), events);
    }

    @Test void restoresUnrelatedCandidateOnlyWhenOriginalStageAndUnchangedDbDecisionAgree() {
        var legacy = legacyResult("NOT_APPLICABLE");
        var facts = new java.util.LinkedHashMap<>(legacy.facts());
        facts.put("generalAnalysis", Map.of("databaseRelevance", "UNRELATED", "databaseRelevanceReason", "표시 설정만 바꿉니다."));
        var corrected = new java.util.LinkedHashMap<>(legacy.result());
        corrected.put("summary", "검토한 문장 수정본");
        var approved = new AnalysisResultResponse(jobId, projectId, "수정본", "LOW", 10,
                legacy.result(), facts, now, "APPROVED", corrected, "문장을 검토했습니다.", reviewerId, now);
        var draft = TrainingCaseDraftBuilder.build(jobWithActivity(List.of()), approved, reviewerId);
        var general = (Map<?, ?>) ((Map<?, ?>) draft.caseDraft().get("answers")).get("general");
        assertThat(general.get("summary")).isEqualTo("검토한 문장 수정본");
        assertThat(general.get("databaseRelevance")).isEqualTo("UNRELATED");
        assertThat(draft.autoFilled()).anyMatch(item -> item.contains("검토용 후보로 복원"));
        assertThat(draft.caseDraft().get("humanApproved")).isEqualTo(false);

        corrected.put("databaseImpact", Map.of("status", "NOT_APPLICABLE", "explanation", "DB 결론을 수정했습니다."));
        var changedDecision = TrainingCaseDraftBuilder.build(jobWithActivity(List.of()), approved, reviewerId);
        assertThat(((Map<?, ?>) ((Map<?, ?>) changedDecision.caseDraft().get("answers")).get("general")).get("databaseRelevance")).isNull();
    }

    private AnalysisResultResponse legacyResult(String databaseStatus) {
        return new AnalysisResultResponse(jobId, projectId, "최종 요약", "LOW", 10,
                Map.of("summary", "최종 요약", "databaseImpact", Map.of("status", databaseStatus,
                        "explanation", "저장된 DB 영향 판단 이유입니다.")),
                Map.of("sourceSummary", Map.of("commitSha", "a".repeat(40)), "databaseSummary", Map.of("tables", List.of()),
                        "relations", List.of()), now, "APPROVED", null, "검토", reviewerId, now);
    }
}
