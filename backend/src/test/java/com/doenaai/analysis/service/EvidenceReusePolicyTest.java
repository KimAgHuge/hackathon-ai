package com.doenaai.analysis.service;

import com.doenaai.analysis.dto.ReusableEvidence;
import com.doenaai.analysis.SourceEvidenceVersion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceReusePolicyTest {
    private static final String SHA = "a".repeat(40);
    private static final Map<String, Object> DB = Map.of("type", "POSTGRESQL", "schema", "public",
            "tables", List.of(Map.of("schema", "public", "name", "users")),
            "warnings", List.of(), "truncated", false);

    @Test void completeMatchingFactsAreReusable() {
        assertThat(EvidenceReusePolicy.canReuse(evidence(false, DB), DB, SHA)).isTrue();
        assertThat(EvidenceReusePolicy.canUseSavedSnapshot(evidence(false, DB), SHA)).isTrue();
        var earlier = new java.util.LinkedHashMap<>(DB);
        earlier.put("capturedAt", "2026-10-15T10:00:00Z");
        var later = new java.util.LinkedHashMap<>(DB);
        later.put("capturedAt", "2026-10-16T10:00:00Z");
        assertThat(EvidenceReusePolicy.canReuse(evidence(false, earlier), later, SHA)).isTrue();
    }

    @Test void partialFactsAllowManualSelectionButNeverAutomaticReuse() {
        assertThat(EvidenceReusePolicy.canReuse(evidence(true, DB), DB, SHA)).isFalse();
        assertThat(EvidenceReusePolicy.canUseSavedSnapshot(evidence(true, DB), SHA)).isTrue();
        assertThat(EvidenceReusePolicy.canUseSavedSnapshot(evidence(false, DB), null)).isFalse();
        assertThat(EvidenceReusePolicy.canReuse(evidence(false, DB), DB, "b".repeat(40))).isFalse();
        assertThat(EvidenceReusePolicy.canReuse(evidence(false, DB), Map.of("type", "POSTGRESQL",
                "schema", "public", "tables", List.of(), "warnings", List.of(), "truncated", false), SHA)).isFalse();
        assertThat(EvidenceReusePolicy.canReuse(evidence(false, DB), Map.of("type", "POSTGRESQL",
                "schema", "public", "tables", List.of(), "warnings", List.of("partial"), "truncated", true), SHA)).isFalse();
    }

    @Test void missingOldFutureOrMalformedCollectorVersionsCannotBeReused() {
        var complete = evidence(false, DB);
        for (Object value : List.of("source-evidence-v1", "source-evidence-v3", 2, false)) {
            var source = new java.util.LinkedHashMap<>(complete.sourceSummary());
            source.put("collectorVersion", value);
            var previous = new ReusableEvidence(complete.sourceJobId(), source, DB, List.of());
            assertThat(EvidenceReusePolicy.canReuse(previous, DB, SHA)).isFalse();
            assertThat(EvidenceReusePolicy.canUseSavedSnapshot(previous, SHA)).isTrue();
            assertThat(EvidenceReusePolicy.savedSnapshotWarnings(previous)).contains("SOURCE_COLLECTOR_OUTDATED");
        }
        var source = new java.util.LinkedHashMap<>(complete.sourceSummary());
        source.remove("collectorVersion");
        var unversioned = new ReusableEvidence(complete.sourceJobId(), source, DB, List.of());
        assertThat(EvidenceReusePolicy.canReuse(unversioned, DB, SHA)).isFalse();
        assertThat(EvidenceReusePolicy.canUseSavedSnapshot(unversioned, SHA)).isTrue();
        assertThat(complete.sourceSummary()).containsEntry("collectorVersion", SourceEvidenceVersion.CURRENT);
    }

    @Test void manualSelectionRequiresCoreArraysAndTheMatchingCommit() {
        var complete = evidence(false, DB);
        assertThat(EvidenceReusePolicy.canUseSavedSnapshot(null, SHA)).isFalse();
        assertThat(EvidenceReusePolicy.canUseSavedSnapshot(complete, "b".repeat(40))).isFalse();
        assertThat(EvidenceReusePolicy.canUseSavedSnapshot(new ReusableEvidence(complete.sourceJobId(),
                Map.of("commitSha", SHA), DB, List.of()), SHA)).isFalse();
        assertThat(EvidenceReusePolicy.canUseSavedSnapshot(new ReusableEvidence(complete.sourceJobId(),
                complete.sourceSummary(), Map.of(), List.of()), SHA)).isFalse();
        assertThat(EvidenceReusePolicy.canUseSavedSnapshot(new ReusableEvidence(complete.sourceJobId(),
                Map.of("commitSha", SHA, "components", List.of("invalid")), DB, List.of()), SHA)).isFalse();
    }

    @Test void savedWarningsDescribeLegacyPartialAndUnknownCoverageWithoutChangingTheOriginal() {
        var source = Map.<String, Object>of("commitSha", SHA, "components", List.of(),
                "promptCoverage", Map.of("truncated", true));
        var database = Map.<String, Object>of("tables", List.of(), "warnings", List.of("일부 수집 경고입니다."));
        var saved = new ReusableEvidence(UUID.randomUUID(), source, database, List.of());
        assertThat(EvidenceReusePolicy.canUseSavedSnapshot(saved, SHA)).isTrue();
        assertThat(EvidenceReusePolicy.canReuse(saved, database, SHA)).isFalse();
        assertThat(EvidenceReusePolicy.savedSnapshotWarnings(saved)).containsExactly("SOURCE_COLLECTOR_OUTDATED",
                "SOURCE_PROMPT_TRUNCATED", "SOURCE_SCAN_STATUS_MISSING", "DATABASE_COVERAGE_MISSING", "DATABASE_WARNINGS_PRESENT");
        assertThat(source).doesNotContainKey("collectorVersion");
        assertThat(database).doesNotContainKey("truncated");
    }

    private ReusableEvidence evidence(boolean truncated, Map<String, Object> database) {
        return new ReusableEvidence(UUID.randomUUID(), Map.of("commitSha", SHA, "components", List.of(),
                "collectorVersion", SourceEvidenceVersion.CURRENT,
                "promptCoverage", Map.of("truncated", truncated),
                "scanMetrics", Map.of("truncated", false)), database, List.of());
    }
}
