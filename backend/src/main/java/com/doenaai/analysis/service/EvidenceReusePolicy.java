package com.doenaai.analysis.service;

import com.doenaai.analysis.dto.ReusableEvidence;
import com.doenaai.analysis.SourceEvidenceVersion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 사용자가 고른 저장 근거와 최신 상태의 자동 재사용 조건을 구분한다. */
final class EvidenceReusePolicy {
    private EvidenceReusePolicy() {}

    /** 저장 시점의 유효한 입력을 확인한다. 일부 수집되었거나 이전 수집기로 만든 자료도 사용할 수 있다. */
    static boolean canUseSavedSnapshot(ReusableEvidence previous, String commitSha) {
        if (previous == null || previous.sourceSummary() == null || previous.databaseSummary() == null
                || previous.relations() == null || commitSha == null || commitSha.isBlank()
                || !Objects.equals(previous.sourceSummary().get("commitSha"), commitSha)) return false;
        return objectList(previous.sourceSummary().get("components"))
                && objectList(previous.databaseSummary().get("tables"))
                && previous.relations().stream().allMatch(Map.class::isInstance);
    }

    private static boolean objectList(Object value) {
        return value instanceof List<?> list && list.stream().allMatch(Map.class::isInstance);
    }

    /** 자동 재사용은 이전과 같이 완전한 최신 수집 결과만 허용한다. */
    private static boolean canUseCompleteSnapshot(ReusableEvidence previous, String commitSha) {
        if (previous == null || previous.sourceSummary() == null || previous.databaseSummary() == null
                || previous.relations() == null || commitSha == null
                || !Objects.equals(previous.sourceSummary().get("commitSha"), commitSha)) return false;
        if (!SourceEvidenceVersion.isCurrent(previous.sourceSummary())) return false;
        if (!(previous.sourceSummary().get("promptCoverage") instanceof Map<?, ?> coverage)
                || !Boolean.FALSE.equals(coverage.get("truncated"))) return false;
        if (!(previous.sourceSummary().get("scanMetrics") instanceof Map<?, ?> scan)
                || !Boolean.FALSE.equals(scan.get("truncated"))) return false;
        return Boolean.FALSE.equals(previous.databaseSummary().get("truncated"))
                && List.of().equals(previous.databaseSummary().get("warnings"));
    }

    static boolean canReuse(ReusableEvidence previous, Map<String, Object> currentDatabase, String commitSha) {
        if (!canUseCompleteSnapshot(previous, commitSha) || currentDatabase == null
                || !Boolean.FALSE.equals(currentDatabase.get("truncated"))) return false;
        if (!List.of().equals(previous.databaseSummary().get("warnings"))
                || !List.of().equals(currentDatabase.get("warnings"))) return false;
        return normalizedDatabase(previous.databaseSummary()).equals(normalizedDatabase(currentDatabase));
    }

    /** 사용을 막는 형식 오류와 구분하여 저장 근거의 한계만 안내한다. */
    static List<String> savedSnapshotWarnings(ReusableEvidence previous) {
        var warnings = new ArrayList<String>();
        if (!SourceEvidenceVersion.isCurrent(previous.sourceSummary())) warnings.add("SOURCE_COLLECTOR_OUTDATED");
        addCoverageWarning(warnings, previous.sourceSummary().get("promptCoverage"),
                "SOURCE_PROMPT_COVERAGE_MISSING", "SOURCE_PROMPT_TRUNCATED");
        addCoverageWarning(warnings, previous.sourceSummary().get("scanMetrics"),
                "SOURCE_SCAN_STATUS_MISSING", "SOURCE_SCAN_TRUNCATED");
        addCoverageWarning(warnings, previous.databaseSummary(), "DATABASE_COVERAGE_MISSING", "DATABASE_TRUNCATED");
        Object databaseWarnings = previous.databaseSummary().get("warnings");
        if (!(databaseWarnings instanceof List<?> list)) warnings.add("DATABASE_WARNINGS_MISSING");
        else if (!list.isEmpty()) warnings.add("DATABASE_WARNINGS_PRESENT");
        return List.copyOf(warnings);
    }

    private static void addCoverageWarning(List<String> warnings, Object value, String missing, String truncated) {
        if (!(value instanceof Map<?, ?> map) || !(map.get("truncated") instanceof Boolean state)) warnings.add(missing);
        else if (state) warnings.add(truncated);
    }

    private static Map<String, Object> normalizedDatabase(Map<String, Object> database) {
        var copy = new java.util.LinkedHashMap<>(database);
        // 수집 시각은 구조 변경이 아니므로 재사용 가능성 비교에서는 제외한다.
        copy.remove("capturedAt");
        if (database.get("tables") instanceof List<?> tables) {
            var sorted = new ArrayList<>(tables);
            sorted.sort(Comparator.comparing(item -> {
                if (!(item instanceof Map<?, ?> table)) return "";
                return table.get("schema") + "." + table.get("name");
            }));
            copy.put("tables", sorted);
        }
        return copy;
    }
}
