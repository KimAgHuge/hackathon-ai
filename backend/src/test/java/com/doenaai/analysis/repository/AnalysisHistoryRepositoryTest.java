package com.doenaai.analysis.repository;

import com.doenaai.analysis.SourceEvidenceVersion;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalysisHistoryRepositoryTest {
    @Test
    void reportsEachMissingOrPartialEvidenceField() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("status")).thenReturn("COMPLETED");
        when(row.getString("result_job_id")).thenReturn("saved");
        when(row.getString("source_commit")).thenReturn("abc");
        when(row.getString("facts_commit")).thenReturn("def");
        when(row.getString("collector_version")).thenReturn(SourceEvidenceVersion.CURRENT);
        when(row.getString("prompt_truncated")).thenReturn(null);
        when(row.getString("scan_truncated")).thenReturn("true");
        when(row.getString("database_truncated")).thenReturn("false");
        when(row.getObject("database_warnings_empty", Boolean.class)).thenReturn(false);
        when(row.getString("relations_type")).thenReturn(null);

        assertThat(AnalysisHistoryRepository.evidenceUnavailableReasons(row)).containsExactly(
                "SOURCE_COMMIT_MISMATCH", "SOURCE_PROMPT_COVERAGE_MISSING", "SOURCE_SCAN_TRUNCATED",
                "DATABASE_WARNINGS_PRESENT", "RELATIONS_MISSING");
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
        ResultSet row = mock(ResultSet.class);
        when(row.getString("status")).thenReturn("COMPLETED");
        when(row.getString("result_job_id")).thenReturn("saved");
        for (String version : new String[]{null, "source-evidence-v1", "source-evidence-v3"}) {
            when(row.getString("collector_version")).thenReturn(version);
            assertThat(AnalysisHistoryRepository.evidenceUnavailableReasons(row)).contains("SOURCE_COLLECTOR_OUTDATED");
        }
    }
}
