package com.doenaai.analysis.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 새 추론·수정본에 같은 변경 유형과 중복·필수 구분 규칙을 적용한다. 구형 값은 임의 변환하지 않는다. */
final class DatabaseChangePolicy {
    static final Set<String> CHANGE_TYPES = Set.of(
            "CREATE_TABLE", "ADD_COLUMN", "ALTER_COLUMN", "DROP_COLUMN", "ADD_CONSTRAINT",
            "ALTER_CONSTRAINT", "ADD_INDEX", "DATA_MIGRATION", "OTHER");

    private DatabaseChangePolicy() {}

    static void validate(Map<?, ?> impact) {
        if (!(impact.get("proposedChanges") instanceof List<?> changes)) throw invalid();
        var additions = new HashSet<List<Object>>();
        boolean classified = false;
        boolean unclassified = false;
        boolean required = false;
        for (var change : changes) {
            if (!(change instanceof Map<?, ?> item)
                    || !(item.get("changeType") instanceof String type) || !CHANGE_TYPES.contains(type)) throw invalid();
            if (Set.of("CREATE_TABLE", "ADD_COLUMN").contains(type)) {
                if (!(item.get("targetTable") instanceof String table) || table.isBlank()) throw invalid();
                Object column = "ADD_COLUMN".equals(type) ? item.get("targetColumn") : "";
                if (column == null || !additions.add(List.of(type, table, column))) {
                    throw new IllegalArgumentException("같은 테이블 또는 컬럼을 중복해서 추가할 수 없습니다.");
                }
            }
            var necessity = item.get("necessity");
            if (necessity == null) unclassified = true;
            else {
                if (!(necessity instanceof String value) || !Set.of("REQUIRED", "RECOMMENDED").contains(value)) throw invalid();
                classified = true;
                required |= "REQUIRED".equals(value);
            }
        }
        if (classified && unclassified) throw new IllegalArgumentException("변경안의 필수·권장 구분을 모두 작성해야 합니다.");
        if (classified && "CHANGE_REQUIRED".equals(impact.get("status")) && !required) {
            throw new IllegalArgumentException("권장 개선만으로 DB 변경 필수를 확정할 수 없습니다.");
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("DB 변경 유형 또는 필수·권장 구분이 올바르지 않습니다.");
    }
}
