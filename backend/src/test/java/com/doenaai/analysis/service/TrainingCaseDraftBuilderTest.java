package com.doenaai.analysis.service;

import com.doenaai.analysis.dto.AnalysisJobResponse;
import com.doenaai.analysis.dto.AnalysisResultResponse;
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
        assertThat(general.get("databaseRelevance")).isNull();
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
        assertThat(response.reviewRequired()).anyMatch(item -> item.startsWith("answers.general.databaseRelevance:"));

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
    }
}
