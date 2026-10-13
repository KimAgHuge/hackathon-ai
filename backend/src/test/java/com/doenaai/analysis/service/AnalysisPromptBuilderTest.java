package com.doenaai.analysis.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AnalysisPromptBuilderTest {
    final JsonMapper mapper = JsonMapper.builder().build();
    final AnalysisPromptBuilder builder = new AnalysisPromptBuilder(mapper, AnalysisPromptBuilder.DEFAULT_MAX_CHARS);

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
        assertThat(mapper.writeValueAsString(payload).length()).isLessThanOrEqualTo(AnalysisPromptBuilder.DEFAULT_MAX_CHARS);
        assertThat(tables).hasSize(100);
        var sent = (Map<?, ?>) payload.get("databaseSummary");
        assertThat(((List<?>) sent.get("tables")).getFirst()).isEqualTo(tables.getLast());
        assertThat(payload.toString()).contains("promptCoverage", "truncated=true", "table_99");
    }

    @Test void includesAllRelationsForAProjectLargerThanThePreviousLimit() {
        var tables = new ArrayList<Map<String, Object>>();
        for (int tableIndex = 0; tableIndex < 26; tableIndex++) {
            var columns = new ArrayList<Map<String, Object>>();
            for (int columnIndex = 0; columnIndex < 24; columnIndex++) {
                columns.add(Map.of("name", "column_" + columnIndex + "_with_long_name",
                        "type", "character varying", "nullable", true));
            }
            tables.add(Map.of("schema", "public", "name", "table_" + tableIndex,
                    "type", "TABLE", "columns", columns, "primaryKey", List.of(),
                    "foreignKeys", List.of(), "indexes", List.of()));
        }
        var relations = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < 61; index++) {
            relations.add(Map.of("schema", "public", "table", "table_" + index % 26,
                    "file", "src/main/java/example/FeatureRepository" + index + ".java",
                    "line", index + 1, "kind", "SQL_SELECT", "confidence", 0.95,
                    "tentative", false, "ambiguous", false, "evidenceType", "STATIC_EXPLICIT"));
        }
        var source = Map.<String, Object>of("components", List.of());
        var database = Map.<String, Object>of("tables", tables);
        var projectId = UUID.randomUUID();
        var original = Map.of("projectId", projectId.toString(), "sourceSummary", source,
                "databaseSummary", database, "relations", relations);
        assertThat(mapper.writeValueAsString(original).length()).isGreaterThan(45_000);

        var previousPayload = new AnalysisPromptBuilder(mapper, 45_000).build(projectId, source, database, relations);
        var previousCoverage = (Map<?, ?>) ((Map<?, ?>) previousPayload.get("sourceSummary")).get("promptCoverage");
        assertThat((int) previousCoverage.get("includedRelations")).isLessThan(61);

        var payload = builder.build(projectId, source, database, relations);
        var coverage = (Map<?, ?>) ((Map<?, ?>) payload.get("sourceSummary")).get("promptCoverage");
        assertThat(mapper.writeValueAsString(payload).length()).isLessThanOrEqualTo(AnalysisPromptBuilder.DEFAULT_MAX_CHARS);
        assertThat(coverage.get("collectedTables")).isEqualTo(26);
        assertThat(coverage.get("includedTables")).isEqualTo(26);
        assertThat(coverage.get("collectedRelations")).isEqualTo(61);
        assertThat(coverage.get("includedRelations")).isEqualTo(61);
        assertThat(coverage.get("truncated")).isEqualTo(false);
        assertThat(relations).hasSize(61);
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
