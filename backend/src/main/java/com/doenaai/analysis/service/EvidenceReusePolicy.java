package com.doenaai.analysis.service;

import com.doenaai.analysis.dto.ReusableEvidence;
import com.doenaai.analysis.SourceEvidenceVersion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 이전 모델 입력이 완전하고 현재 DB 구조가 동일할 때에만 Git 스캔 근거를 재사용한다. */
final class EvidenceReusePolicy {
    private EvidenceReusePolicy() {}

    /** 사용자 확인을 받은 저장 스냅샷 자체가 완전한지 검사한다. 현재 원격 상태와 같다는 뜻은 아니다. */
    static boolean canUseSavedSnapshot(ReusableEvidence previous, String commitSha) {
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
        if (!canUseSavedSnapshot(previous, commitSha) || currentDatabase == null
                || !Boolean.FALSE.equals(currentDatabase.get("truncated"))) return false;
        if (!List.of().equals(previous.databaseSummary().get("warnings"))
                || !List.of().equals(currentDatabase.get("warnings"))) return false;
        return normalizedDatabase(previous.databaseSummary()).equals(normalizedDatabase(currentDatabase));
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
