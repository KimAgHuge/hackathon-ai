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

    @Test void incompleteOrChangedFactsAreNotReusable() {
        assertThat(EvidenceReusePolicy.canReuse(evidence(true, DB), DB, SHA)).isFalse();
        assertThat(EvidenceReusePolicy.canUseSavedSnapshot(evidence(true, DB), SHA)).isFalse();
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
            assertThat(EvidenceReusePolicy.canUseSavedSnapshot(previous, SHA)).isFalse();
        }
        var source = new java.util.LinkedHashMap<>(complete.sourceSummary());
        source.remove("collectorVersion");
        var unversioned = new ReusableEvidence(complete.sourceJobId(), source, DB, List.of());
        assertThat(EvidenceReusePolicy.canReuse(unversioned, DB, SHA)).isFalse();
        assertThat(EvidenceReusePolicy.canUseSavedSnapshot(unversioned, SHA)).isFalse();
        assertThat(complete.sourceSummary()).containsEntry("collectorVersion", SourceEvidenceVersion.CURRENT);
    }

    private ReusableEvidence evidence(boolean truncated, Map<String, Object> database) {
        return new ReusableEvidence(UUID.randomUUID(), Map.of("commitSha", SHA,
                "collectorVersion", SourceEvidenceVersion.CURRENT,
                "promptCoverage", Map.of("truncated", truncated),
                "scanMetrics", Map.of("truncated", false)), database, List.of());
    }
}
