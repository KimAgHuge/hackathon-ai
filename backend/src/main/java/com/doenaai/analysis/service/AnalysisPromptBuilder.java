package com.doenaai.analysis.service;

import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.util.*;

/** Bound context size and preserve explicit coverage information for the model and UI. */
@Service
@RequiredArgsConstructor
public class AnalysisPromptBuilder {
    private final ObjectMapper mapper;
    static final int MAX_CHARS = 45_000;
    private static final Set<String> SECRET_KEYS = Set.of("password", "token", "accesstoken", "apikey",
            "authorization", "ciphertext", "credential", "dbpassword", "passphrase", "privatekey", "secret");

    @SuppressWarnings("unchecked")
    public Map<String, Object> build(UUID projectId, Map<String, Object> source,
                                      Map<String, Object> database, List<Map<String, Object>> relations) {
        Map<String, Object> payload = mapper.convertValue(Map.of("projectId", projectId.toString(),
                "sourceSummary", source, "databaseSummary", database, "relations", relations),
                new TypeReference<>() {});
        rejectSensitiveFields(payload);
        var sourceCopy = (Map<String, Object>) payload.get("sourceSummary");
        // Collector currently exposes the same technology facts under both names.
        sourceCopy.remove("technologies");
        int omittedFrameworkEvidences = 0;
        if (sourceCopy.get("frameworks") instanceof List<?> frameworks) {
            for (Object item : frameworks) {
                if (item instanceof Map<?, ?> framework && framework.get("evidences") instanceof List<?> evidences) {
                    omittedFrameworkEvidences += Math.max(0, evidences.size() - 3);
                    trim(evidences, 3);
                }
            }
        }
        var databaseCopy = (Map<String, Object>) payload.get("databaseSummary");
        var tables = (List<Map<String, Object>>) databaseCopy.getOrDefault("tables", new ArrayList<>());
        var references = (List<Map<String, Object>>) payload.get("relations");
        int totalTables = tables.size(), totalRelations = references.size();
        Set<String> referenced = new HashSet<>();
        relations.forEach(r -> referenced.add(r.get("schema") + "." + r.get("table")));
        tables.sort(Comparator.comparing(t -> !referenced.contains(t.get("schema") + "." + t.get("name"))));
        var components = (List<?>) sourceCopy.getOrDefault("components", new ArrayList<>());
        int totalComponents = components.size();
        trim(components, 150);
        trim(references, 200);
        trim(tables, 40);
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("collectedTables", totalTables);
        coverage.put("collectedRelations", totalRelations);
        coverage.put("collectedComponents", totalComponents);
        coverage.put("omittedFrameworkEvidences", omittedFrameworkEvidences);
        coverage.put("notice", "수집 및 모델 입력 범위만 분석합니다. 누락된 관계가 없다는 보장은 아닙니다.");
        sourceCopy.put("promptCoverage", coverage);
        updateCoverage(coverage, tables, references, components);
        while (mapper.writeValueAsString(payload).length() > MAX_CHARS) {
            if (components.size() > 10) components.removeLast();
            else if (references.size() > 10) references.removeLast();
            else if (tables.size() > 1) tables.removeLast();
            else throw new IllegalStateException("Analysis input exceeds context limit; narrow the schema or requirement.");
            updateCoverage(coverage, tables, references, components);
        }
        // Do not retain references to a table omitted from model context.
        Set<String> included = new HashSet<>();
        tables.forEach(t -> included.add(t.get("schema") + "." + t.get("name")));
        references.removeIf(r -> !included.contains(r.get("schema") + "." + r.get("table")));
        updateCoverage(coverage, tables, references, components);
        return payload;
    }

    private void updateCoverage(Map<String, Object> coverage, List<?> tables, List<?> relations, List<?> components) {
        coverage.put("includedTables", tables.size());
        coverage.put("includedRelations", relations.size());
        coverage.put("includedComponents", components.size());
        coverage.put("truncated", !coverage.get("collectedTables").equals(tables.size())
                || !coverage.get("collectedRelations").equals(relations.size())
                || !coverage.get("collectedComponents").equals(components.size())
                || !coverage.get("omittedFrameworkEvidences").equals(0));
    }

    private void trim(List<?> values, int max) {
        while (values.size() > max) values.removeLast();
    }

    private void rejectSensitiveFields(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                String key = entry.getKey().toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                if (SECRET_KEYS.contains(key)) throw new IllegalStateException("Sensitive fields in analysis input.");
                rejectSensitiveFields(entry.getValue());
            }
        } else if (value instanceof List<?> list) {
            list.forEach(this::rejectSensitiveFields);
        }
    }
}
