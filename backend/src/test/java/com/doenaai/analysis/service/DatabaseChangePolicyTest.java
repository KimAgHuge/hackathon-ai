package com.doenaai.analysis.service;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class DatabaseChangePolicyTest {
    @Test void rejectsDuplicateColumnAndTableAdditions() {
        for (String type : List.of("ADD_COLUMN", "CREATE_TABLE")) {
            var change = change(type, "REQUIRED");
            assertThatThrownBy(() -> validate(List.of(change, change))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void acceptsRequiredColumnWithSeparateRecommendedIndex() {
        assertThatCode(() -> validate(List.of(change("ADD_COLUMN", "REQUIRED"), change("ADD_INDEX", "RECOMMENDED"))))
                .doesNotThrowAnyException();
    }

    @Test void rejectsRecommendationOnlyAndPartiallyClassifiedChanges() {
        assertThatThrownBy(() -> validate(List.of(change("ADD_INDEX", "RECOMMENDED"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate(List.of(change("ADD_COLUMN", "REQUIRED"), change("ADD_INDEX", null))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void keepsLegacyOtherAndMissingClassificationReadable() {
        assertThatCode(() -> validate(List.of(change("OTHER", null)))).doesNotThrowAnyException();
    }

    @Test void rejectsUnknownTypesAndClassificationValues() {
        assertThatThrownBy(() -> validate(List.of(change("INVALID", "REQUIRED")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate(List.of(change("ADD_COLUMN", "INVALID")))).isInstanceOf(IllegalArgumentException.class);
    }

    private void validate(List<Map<String, Object>> changes) {
        DatabaseChangePolicy.validate(Map.of("status", "CHANGE_REQUIRED", "proposedChanges", changes));
    }

    private Map<String, Object> change(String type, String necessity) {
        var change = new LinkedHashMap<String, Object>();
        change.put("targetTable", "example.table_example");
        change.put("targetColumn", "column_example");
        change.put("changeType", type);
        if (necessity != null) change.put("necessity", necessity);
        return change;
    }
}
