package com.doenaai.analysis.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AnalysisPromptBuilderTest {
    final JsonMapper mapper = JsonMapper.builder().build();
    final AnalysisPromptBuilder builder = new AnalysisPromptBuilder(mapper);

    @Test void boundedContextPrioritizesReferencedTablesWithoutMutatingCollectedFacts() {
        var tables = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 100; i++) {
            tables.add(Map.of("schema", "public", "name", "table_" + i,
                    "columns", List.of(Map.of("name", "column" + "x".repeat(1500), "type", "text"))));
        }
        var relations = List.of(Map.<String, Object>of("schema", "public", "table", "table_99",
                "file", "Repository.java", "line", 3));
        var payload = builder.build(UUID.randomUUID(), Map.of("components", List.of()),
                Map.of("tables", tables), relations);
        assertThat(mapper.writeValueAsString(payload).length()).isLessThanOrEqualTo(AnalysisPromptBuilder.MAX_CHARS);
        assertThat(tables).hasSize(100);
        var sent = (Map<?, ?>) payload.get("databaseSummary");
        assertThat(((List<?>) sent.get("tables")).getFirst()).isEqualTo(tables.getLast());
        assertThat(payload.toString()).contains("promptCoverage", "truncated=true", "table_99");
    }

    @Test void rejectsNestedSecretFieldsButAllowsDatabaseColumnNamedPassword() {
        assertThatThrownBy(() -> builder.build(UUID.randomUUID(),
                Map.of("changeRequirement", Map.of("accessToken", "fixture")), Map.of(), List.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("fixture");
        assertThatCode(() -> builder.build(UUID.randomUUID(), Map.of(), Map.of("tables", List.of(
                Map.of("schema", "public", "name", "users", "columns", List.of(Map.of("name", "password"))))), List.of()))
                .doesNotThrowAnyException();
    }
}
