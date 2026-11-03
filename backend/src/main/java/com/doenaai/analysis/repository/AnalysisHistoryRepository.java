package com.doenaai.analysis.repository;

import com.doenaai.analysis.SourceEvidenceVersion;
import com.doenaai.analysis.dto.AnalysisHistoryItem;
import com.doenaai.analysis.dto.AnalysisHistoryPage;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class AnalysisHistoryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    /** 이미 접근 검증된 프로젝트의 작업만 페이지 단위로 읽는다. */
    public AnalysisHistoryPage find(List<UUID> projectIds, int page, int size) {
        return find(projectIds, page, size, false);
    }

    /** 저장 자료 후보는 SQL에서 먼저 골라 이전 자료도 페이지 단위로 조회한다. */
    public AnalysisHistoryPage find(List<UUID> projectIds, int page, int size, boolean reusableEvidenceOnly) {
        if (projectIds.isEmpty()) return new AnalysisHistoryPage(List.of(), 0, page, size);
        var params = new MapSqlParameterSource("projectIds", projectIds)
                .addValue("limit", size).addValue("offset", page * size);
        String evidenceFilter = reusableEvidenceOnly ? " and " + AnalysisResultRepository.SAVED_EVIDENCE_CONDITION : "";
        Long total = jdbc.queryForObject("""
                select count(*) from public.analysis_jobs j
                left join public.analysis_results r on r.analysis_job_id = j.id
                where j.project_id in (:projectIds)
                """ + evidenceFilter, params, Long.class);
        List<AnalysisHistoryItem> items = jdbc.query("""
                select j.id, j.project_id, p.name as project_name, j.requirement_version_id,
                       rv.title as requirement_title, j.status, r.risk_level, r.summary,
                       coalesce(r.approved, false) as approved, r.review_status,
                       j.analysis_options ->> 'evidenceReusedFromJobId' as evidence_reused_from_job_id,
                       j.input_snapshot ->> 'commitSha' as source_commit,
                       r.analysis_job_id as result_job_id,
                       r.inference_metadata -> 'facts' -> 'sourceSummary' ->> 'commitSha' as facts_commit,
                       r.inference_metadata -> 'facts' -> 'sourceSummary' ->> 'collectorVersion' as collector_version,
                       case when jsonb_typeof(r.inference_metadata -> 'facts' -> 'sourceSummary' -> 'promptCoverage' -> 'truncated') = 'boolean'
                         then r.inference_metadata -> 'facts' -> 'sourceSummary' -> 'promptCoverage' ->> 'truncated' end as prompt_truncated,
                       case when jsonb_typeof(r.inference_metadata -> 'facts' -> 'sourceSummary' -> 'scanMetrics' -> 'truncated') = 'boolean'
                         then r.inference_metadata -> 'facts' -> 'sourceSummary' -> 'scanMetrics' ->> 'truncated' end as scan_truncated,
                       case when jsonb_typeof(r.inference_metadata -> 'facts' -> 'databaseSummary' -> 'truncated') = 'boolean'
                         then r.inference_metadata -> 'facts' -> 'databaseSummary' ->> 'truncated' end as database_truncated,
                       (r.inference_metadata -> 'facts' -> 'databaseSummary' -> 'warnings') = '[]'::jsonb as database_warnings_empty,
                       jsonb_typeof(r.inference_metadata -> 'facts' -> 'databaseSummary' -> 'warnings') as database_warnings_type,
                       jsonb_typeof(r.inference_metadata -> 'facts' -> 'sourceSummary') as source_summary_type,
                       jsonb_typeof(r.inference_metadata -> 'facts' -> 'databaseSummary') as database_summary_type,
                       jsonb_typeof(r.inference_metadata -> 'facts' -> 'sourceSummary' -> 'components') as components_type,
                       jsonb_typeof(r.inference_metadata -> 'facts' -> 'databaseSummary' -> 'tables') as tables_type,
                       jsonb_typeof(r.inference_metadata -> 'facts' -> 'relations') as relations_type,
                       (
                """ + AnalysisResultRepository.SAVED_EVIDENCE_CONDITION + """
                       ) as reusable_evidence,
                       j.created_at, j.completed_at, j.thinking_enabled, j.thinking_effort
                from public.analysis_jobs j
                join public.projects p on p.id = j.project_id
                left join public.requirement_versions rv on rv.id = j.requirement_version_id
                left join public.analysis_results r on r.analysis_job_id = j.id
                where j.project_id in (:projectIds)
                """ + evidenceFilter + """
                order by j.created_at desc, j.id desc
                limit :limit offset :offset
                """, params, this::mapRow);
        return new AnalysisHistoryPage(items, total == null ? 0 : total, page, size);
    }

    private AnalysisHistoryItem mapRow(ResultSet rs, int ignored) throws SQLException {
        String reused = rs.getString("evidence_reused_from_job_id");
        boolean reusable = rs.getBoolean("reusable_evidence");
        return new AnalysisHistoryItem(
                rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getString("project_name"), rs.getObject("requirement_version_id", UUID.class),
                rs.getString("requirement_title"), rs.getString("status"), rs.getString("risk_level"),
                rs.getString("summary"), rs.getBoolean("approved"), rs.getString("review_status"),
                reused == null ? null : UUID.fromString(reused),
                reusable, reusable ? List.of() : evidenceUnavailableReasons(rs), rs.getString("source_commit"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class),
                rs.getObject("thinking_enabled", Boolean.class), rs.getString("thinking_effort"),
                reusable ? evidenceWarnings(rs) : List.of());
    }

    /** 실제 사용을 막는 필수 구조와 커밋 정보의 결손만 반환한다. */
    static List<String> evidenceUnavailableReasons(ResultSet rs) throws SQLException {
        List<String> reasons = new ArrayList<>();
        if (!"COMPLETED".equals(rs.getString("status"))) {
            reasons.add("ANALYSIS_NOT_COMPLETED");
            return reasons;
        }
        if (rs.getString("result_job_id") == null) {
            reasons.add("RESULT_MISSING");
            return reasons;
        }

        String sourceCommit = rs.getString("source_commit");
        String factsCommit = rs.getString("facts_commit");
        if (sourceCommit == null || sourceCommit.isBlank()) reasons.add("SOURCE_COMMIT_MISSING");
        if (factsCommit == null || factsCommit.isBlank()) reasons.add("SAVED_COMMIT_MISSING");
        if (sourceCommit != null && factsCommit != null && !sourceCommit.equals(factsCommit)) {
            reasons.add("SOURCE_COMMIT_MISMATCH");
        }
        if (!"object".equals(rs.getString("source_summary_type"))) reasons.add("SOURCE_EVIDENCE_MISSING");
        if (!"object".equals(rs.getString("database_summary_type"))) reasons.add("DATABASE_EVIDENCE_MISSING");
        if (!"array".equals(rs.getString("components_type"))) reasons.add("SOURCE_COMPONENTS_MISSING");
        if (!"array".equals(rs.getString("tables_type"))) reasons.add("DATABASE_TABLES_MISSING");
        if (!"array".equals(rs.getString("relations_type"))) reasons.add("RELATIONS_MISSING");
        if (reasons.isEmpty()) reasons.add("EVIDENCE_FORMAT_UNSUPPORTED");
        return reasons;
    }

    /** 부분 수집과 이전 수집 방식은 선택 가능한 자료의 검토 한계로 안내한다. */
    static List<String> evidenceWarnings(ResultSet rs) throws SQLException {
        var warnings = new ArrayList<String>();
        if (!SourceEvidenceVersion.CURRENT.equals(rs.getString("collector_version"))) warnings.add("SOURCE_COLLECTOR_OUTDATED");
        addTruncationReason(warnings, rs.getString("prompt_truncated"), "SOURCE_PROMPT_COVERAGE_MISSING", "SOURCE_PROMPT_TRUNCATED");
        addTruncationReason(warnings, rs.getString("scan_truncated"), "SOURCE_SCAN_STATUS_MISSING", "SOURCE_SCAN_TRUNCATED");
        addTruncationReason(warnings, rs.getString("database_truncated"), "DATABASE_COVERAGE_MISSING", "DATABASE_TRUNCATED");
        Boolean warningsEmpty = rs.getObject("database_warnings_empty", Boolean.class);
        if (!"array".equals(rs.getString("database_warnings_type")) || warningsEmpty == null) warnings.add("DATABASE_WARNINGS_MISSING");
        else if (!warningsEmpty) warnings.add("DATABASE_WARNINGS_PRESENT");
        return warnings;
    }

    private static void addTruncationReason(List<String> reasons, String value, String missingCode, String truncatedCode) {
        if (!"true".equals(value) && !"false".equals(value)) reasons.add(missingCode);
        else if ("true".equals(value)) reasons.add(truncatedCode);
    }
}
