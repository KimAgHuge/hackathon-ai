package com.doenaai.analysis.service;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/** AI 입력 크기를 제한하고 실제 전달 범위를 모델·화면에서 확인할 수 있게 기록한다. */
@Service
public class AnalysisPromptBuilder {
    private final ObjectMapper mapper;
    static final int DEFAULT_MAX_CHARS = 90_000;
    private final int maxChars;
    private static final Set<String> SECRET_KEYS = Set.of("password", "token", "accesstoken", "apikey",
            "authorization", "ciphertext", "credential", "dbpassword", "passphrase", "privatekey", "secret");

    public AnalysisPromptBuilder(ObjectMapper mapper,
                                 @Value("${doenaai.analysis.max-prompt-chars:90000}") int maxChars) {
        if (maxChars < 1_000) throw new IllegalArgumentException("Analysis input limit must be at least 1000 characters.");
        this.mapper = mapper;
        this.maxChars = maxChars;
    }

    /**
     * AI에 전달할 사실을 복사·검사하고 크기 제한에 맞게 줄인다.
     *
     * @param projectId 분석 대상 프로젝트 ID
     * @param source 소스 스캔 결과 및 선택된 변경 요구사항
     * @param database 읽기 전용으로 수집한 DB 구조
     * @param relations 소스와 DB 테이블의 정적 참조 목록
     * @return 비밀 필드를 검사하고 크기·범위 제한을 적용한 AI 요청 본문
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> build(UUID projectId, Map<String, Object> source,
                                      Map<String, Object> database, List<Map<String, Object>> relations) {
        Map<String, Object> payload = mapper.convertValue(Map.of("projectId", projectId.toString(),
                "sourceSummary", source, "databaseSummary", database, "relations", relations),
                new TypeReference<>() {});
        rejectSensitiveFields(payload);
        var sourceCopy = (Map<String, Object>) payload.get("sourceSummary");
        var connections = sourceCopy.get("sourceConnections") instanceof Map<?, ?> rawConnections
                ? (Map<String, Object>) rawConnections : new LinkedHashMap<String, Object>();
        var links = (List<?>) connections.getOrDefault("links", new ArrayList<>());
        var routes = (List<?>) connections.getOrDefault("routes", new ArrayList<>());
        var unresolved = (List<?>) connections.getOrDefault("unresolvedReferences", new ArrayList<>());
        int totalLinks = links.size(), totalRoutes = routes.size();
        int totalUnresolved = unresolved.size();
        trim(unresolved, 30);
        connections.put("collectedUnresolvedReferences", totalUnresolved);
        connections.put("includedUnresolvedReferences", unresolved.size());
        boolean connectionsAlreadyTruncated = Boolean.TRUE.equals(connections.get("truncated")) || totalUnresolved != unresolved.size();
        trim(links, 150);
        trim(routes, 80);
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
        components = diverseComponents(components);
        sourceCopy.put("components", components);
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
        updateConnections(connections, totalLinks, totalRoutes, links, routes, connectionsAlreadyTruncated);
        coverage.put("sourceConnectionsTruncated", Boolean.TRUE.equals(connections.get("truncated")));
        updateCoverage(coverage, tables, references, components);
        while (mapper.writeValueAsString(payload).length() > maxChars) {
            if (components.size() > 10) components.removeLast();
            else if (!links.isEmpty()) links.removeLast();
            else if (!routes.isEmpty()) routes.removeLast();
            else if (!unresolved.isEmpty()) {
                unresolved.removeLast();
                connections.put("includedUnresolvedReferences", unresolved.size());
                connectionsAlreadyTruncated = true;
            }
            else if (references.size() > 10) references.removeLast();
            else if (tables.size() > 1) tables.removeLast();
            else throw new IllegalStateException("Analysis input exceeds context limit; narrow the schema or requirement.");
            updateConnections(connections, totalLinks, totalRoutes, links, routes, connectionsAlreadyTruncated);
            coverage.put("sourceConnectionsTruncated", Boolean.TRUE.equals(connections.get("truncated")));
            updateCoverage(coverage, tables, references, components);
        }
        // Do not retain references to a table omitted from model context.
        Set<String> included = new HashSet<>();
        tables.forEach(t -> included.add(t.get("schema") + "." + t.get("name")));
        references.removeIf(r -> !included.contains(r.get("schema") + "." + r.get("table")));
        updateCoverage(coverage, tables, references, components);
        if (mapper.writeValueAsString(payload).length() > maxChars) {
            throw new IllegalStateException("소스 연결 범위 표시를 포함한 입력이 크기 제한을 초과했습니다.");
        }
        return payload;
    }

    private void updateConnections(Map<String, Object> connections, int totalLinks, int totalRoutes,
                                   List<?> links, List<?> routes, boolean alreadyTruncated) {
        connections.put("collectedLinks", totalLinks);
        connections.put("includedLinks", links.size());
        connections.put("collectedRoutes", totalRoutes);
        connections.put("includedRoutes", routes.size());
        connections.put("truncated", alreadyTruncated || totalLinks != links.size() || totalRoutes != routes.size());
    }

    /** 한 언어의 파일이 입력 상한을 먼저 채우지 않도록 역할·언어별로 번갈아 선택한다. */
    private List<?> diverseComponents(List<?> values) {
        var groups = new LinkedHashMap<String, ArrayDeque<Object>>();
        for (Object value : values) {
            String role = value instanceof Map<?, ?> component ? String.valueOf(component.get("type")) : "SOURCE";
            String language = value instanceof Map<?, ?> component ? String.valueOf(component.get("language")) : "";
            groups.computeIfAbsent(role + ":" + language, ignored -> new ArrayDeque<>()).add(value);
        }
        var selected = new ArrayList<>();
        while (selected.size() < values.size()) for (var group : groups.values()) if (!group.isEmpty()) selected.add(group.removeFirst());
        return selected;
    }

    private void updateCoverage(Map<String, Object> coverage, List<?> tables, List<?> relations, List<?> components) {
        coverage.put("includedTables", tables.size());
        coverage.put("includedRelations", relations.size());
        coverage.put("includedComponents", components.size());
        coverage.put("truncated", !coverage.get("collectedTables").equals(tables.size())
                || !coverage.get("collectedRelations").equals(relations.size())
                || !coverage.get("collectedComponents").equals(components.size())
                || !coverage.get("omittedFrameworkEvidences").equals(0)
                || Boolean.TRUE.equals(coverage.get("sourceConnectionsTruncated")));
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
