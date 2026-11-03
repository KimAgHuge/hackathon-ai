package com.doenaai.analysis.repository;

import com.doenaai.analysis.SourceEvidenceVersion;
import com.doenaai.analysis.dto.AnalysisHistoryItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class AnalysisHistoryRepositoryTest {
    @Test
    void missingCoreFieldsBlockSelectionWhilePartialCoverageOnlyProducesWarnings() throws Exception {
        ResultSet row = validRow();
        when(row.getString("source_commit")).thenReturn("abc");
        when(row.getString("facts_commit")).thenReturn("def");
        when(row.getString("prompt_truncated")).thenReturn(null);
        when(row.getString("scan_truncated")).thenReturn("true");
        when(row.getString("database_truncated")).thenReturn("false");
        when(row.getObject("database_warnings_empty", Boolean.class)).thenReturn(false);
        when(row.getString("relations_type")).thenReturn(null);

        assertThat(AnalysisHistoryRepository.evidenceUnavailableReasons(row)).containsExactly(
                "SOURCE_COMMIT_MISMATCH", "RELATIONS_MISSING");
        assertThat(AnalysisHistoryRepository.evidenceWarnings(row)).containsExactly(
                "SOURCE_PROMPT_COVERAGE_MISSING", "SOURCE_SCAN_TRUNCATED", "DATABASE_WARNINGS_PRESENT");
    }

    @Test
    void incompleteJobDoesNotClaimMissingEvidence() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("status")).thenReturn("RUNNING");

        assertThat(AnalysisHistoryRepository.evidenceUnavailableReasons(row))
                .isEqualTo(List.of("ANALYSIS_NOT_COMPLETED"));
    }

    @Test
    void reportsAnOutdatedCollectorForMissingAndDifferentVersions() throws Exception {
        ResultSet row = validRow();
        for (String version : new String[]{null, "source-evidence-v1", "source-evidence-v3"}) {
            when(row.getString("collector_version")).thenReturn(version);
            assertThat(AnalysisHistoryRepository.evidenceWarnings(row)).containsExactly("SOURCE_COLLECTOR_OUTDATED");
            assertThat(AnalysisHistoryRepository.evidenceUnavailableReasons(row)).doesNotContain("SOURCE_COLLECTOR_OUTDATED");
        }
    }

    @Test
    void missingOrMalformedCoverageStaysAnUnknownWarning() throws Exception {
        ResultSet row = validRow();
        when(row.getString("prompt_truncated")).thenReturn("invalid");
        when(row.getString("scan_truncated")).thenReturn(null);
        when(row.getString("database_truncated")).thenReturn(null);
        when(row.getString("database_warnings_type")).thenReturn("object");

        assertThat(AnalysisHistoryRepository.evidenceWarnings(row)).containsExactly(
                "SOURCE_PROMPT_COVERAGE_MISSING", "SOURCE_SCAN_STATUS_MISSING", "DATABASE_COVERAGE_MISSING", "DATABASE_WARNINGS_MISSING");
    }

    @Test
    void savedEvidenceFilterUsesTheSameEligibilityBeforeCountingAndPaging() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(3L);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<AnalysisHistoryItem>>any()))
                .thenReturn(List.of());
        UUID projectId = UUID.randomUUID();

        var page = new AnalysisHistoryRepository(jdbc).find(List.of(projectId), 1, 20, true);

        var countSql = ArgumentCaptor.forClass(String.class);
        var pageSql = ArgumentCaptor.forClass(String.class);
        var params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).queryForObject(countSql.capture(), any(MapSqlParameterSource.class), eq(Long.class));
        verify(jdbc).query(pageSql.capture(), params.capture(), org.mockito.ArgumentMatchers.<RowMapper<AnalysisHistoryItem>>any());
        assertThat(countSql.getValue()).contains(AnalysisResultRepository.SAVED_EVIDENCE_CONDITION, "j.project_id in (:projectIds)");
        assertThat(pageSql.getValue()).contains(AnalysisResultRepository.SAVED_EVIDENCE_CONDITION,
                "j.project_id in (:projectIds)", "limit :limit offset :offset");
        assertThat(params.getValue().getValue("projectIds")).isEqualTo(List.of(projectId));
        assertThat(params.getValue().getValue("offset")).isEqualTo(20);
        assertThat(page.total()).isEqualTo(3);
    }

    private ResultSet validRow() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("status")).thenReturn("COMPLETED");
        when(row.getString("result_job_id")).thenReturn("saved");
        when(row.getString("source_commit")).thenReturn("abc");
        when(row.getString("facts_commit")).thenReturn("abc");
        when(row.getString("collector_version")).thenReturn(SourceEvidenceVersion.CURRENT);
        when(row.getString("prompt_truncated")).thenReturn("false");
        when(row.getString("scan_truncated")).thenReturn("false");
        when(row.getString("database_truncated")).thenReturn("false");
        when(row.getObject("database_warnings_empty", Boolean.class)).thenReturn(true);
        when(row.getString("database_warnings_type")).thenReturn("array");
        when(row.getString("source_summary_type")).thenReturn("object");
        when(row.getString("database_summary_type")).thenReturn("object");
        for (String field : List.of("components_type", "tables_type", "relations_type")) {
            when(row.getString(field)).thenReturn("array");
        }
        return row;
    }
}
