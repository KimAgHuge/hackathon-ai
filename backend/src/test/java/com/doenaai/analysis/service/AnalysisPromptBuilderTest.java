package com.doenaai.analysis.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AnalysisPromptBuilderTest {
    final JsonMapper mapper = JsonMapper.builder().build();
    final AnalysisPromptBuilder builder = new AnalysisPromptBuilder(mapper, AnalysisPromptBuilder.DEFAULT_MAX_CHARS);

    @Test void preservesDifferentComponentRolesAndReportsOmittedConnections() {
        var components = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < 200; index++) components.add(Map.of("path", "app/Store" + index + ".py", "type", "SOURCE", "language", "Python"));
        components.add(Map.of("path", "web/Page.jsx", "type", "SOURCE", "language", "JavaScript"));
        var links = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < 160; index++) links.add(Map.of("file", "web/Page.jsx", "line", index + 1, "targetFile", "app/Store.py"));
        var payload = builder.build(UUID.randomUUID(), Map.of("components", components,
                "sourceConnections", Map.of("links", links, "routes", List.of(), "unresolvedReferences", List.of())), Map.of(), List.of());
        var source = (Map<?, ?>) payload.get("sourceSummary");
        assertThat(source.toString()).contains("web/Page.jsx", "collectedLinks=160", "includedLinks=150", "sourceConnectionsTruncated=true");
        assertThat((List<?>) source.get("components")).hasSize(150);
        assertThat(components).hasSize(201);
        assertThat(links).hasSize(160);
    }

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

    @Test void rebuildingSavedInputsPreservesEarlierOmissionsCountsAndGraphLimits() {
        var oldCoverage = Map.<String, Object>of("collectedComponents", 30, "collectedTables", 7,
                "collectedRelations", 9, "omittedFrameworkEvidences", 2, "truncated", true);
        var graph = Map.<String, Object>of("links", List.of(Map.of("file", "src/one.py", "targetFile", "src/two.py")),
                "routes", List.of(), "unresolvedReferences", List.of(),
                "collectedLinks", 12, "collectedRoutes", 6, "collectedUnresolvedReferences", 3, "truncated", true);
        var source = Map.<String, Object>of("components", List.of(Map.of("path", "src/one.py")),
                "promptCoverage", oldCoverage, "sourceConnections", graph,
                "scanMetrics", Map.of("truncated", true), "collectorVersion", "source-evidence-v1");
        var database = Map.<String, Object>of("tables", List.of(), "truncated", true, "warnings", List.of("이전 수집 경고입니다."));

        var payload = builder.build(UUID.randomUUID(), source, database, List.of());

        var sentSource = (Map<?, ?>) payload.get("sourceSummary");
        var coverage = (Map<?, ?>) sentSource.get("promptCoverage");
        assertThat(coverage.get("collectedComponents")).isEqualTo(30);
        assertThat(coverage.get("collectedTables")).isEqualTo(7);
        assertThat(coverage.get("collectedRelations")).isEqualTo(9);
        assertThat(coverage.get("omittedFrameworkEvidences")).isEqualTo(2);
        assertThat(coverage.get("includedComponents")).isEqualTo(1);
        assertThat(coverage.get("truncated")).isEqualTo(true);
        assertThat(coverage.get("upstreamTruncated")).isEqualTo(true);
        assertThat(((Map<?, ?>) sentSource.get("sourceConnections")).get("collectedLinks")).isEqualTo(12);
        assertThat(((Map<?, ?>) sentSource.get("sourceConnections")).get("collectedRoutes")).isEqualTo(6);
        assertThat(((Map<?, ?>) sentSource.get("sourceConnections")).get("collectedUnresolvedReferences")).isEqualTo(3);
        assertThat(sentSource.get("collectorVersion")).isEqualTo("source-evidence-v1");
        assertThat(source.get("promptCoverage")).isSameAs(oldCoverage);
        assertThat(payload.get("databaseSummary")).isEqualTo(database);
    }

    @Test void unknownSavedCoverageCannotBecomeACompleteSnapshot() {
        var payload = builder.build(UUID.randomUUID(), Map.of("components", List.of(),
                "promptCoverage", Map.of("upstreamCoverageUnknown", true)), Map.of("tables", List.of()), List.of());
        var coverage = (Map<?, ?>) ((Map<?, ?>) payload.get("sourceSummary")).get("promptCoverage");

        assertThat(coverage.get("upstreamCoverageUnknown")).isEqualTo(true);
        assertThat(coverage.get("truncated")).isEqualTo(true);
    }
}
