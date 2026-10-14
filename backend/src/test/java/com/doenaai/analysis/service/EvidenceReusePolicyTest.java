package com.doenaai.analysis.service;

import com.doenaai.analysis.dto.ReusableEvidence;
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

    private ReusableEvidence evidence(boolean truncated, Map<String, Object> database) {
        return new ReusableEvidence(UUID.randomUUID(), Map.of("commitSha", SHA,
                "promptCoverage", Map.of("truncated", truncated),
                "scanMetrics", Map.of("truncated", false)), database, List.of());
    }
}
