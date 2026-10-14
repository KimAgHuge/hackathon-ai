package com.doenaai.analysis.repository;

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
        if (projectIds.isEmpty()) return new AnalysisHistoryPage(List.of(), 0, page, size);
        var params = new MapSqlParameterSource("projectIds", projectIds)
                .addValue("limit", size).addValue("offset", page * size);
        Long total = jdbc.queryForObject("""
                select count(*) from public.analysis_jobs j
                where j.project_id in (:projectIds)
                """, params, Long.class);
        List<AnalysisHistoryItem> items = jdbc.query("""
                select j.id, j.project_id, p.name as project_name, j.requirement_version_id,
                       rv.title as requirement_title, j.status, r.risk_level, r.summary,
                       coalesce(r.approved, false) as approved,
                       j.analysis_options ->> 'evidenceReusedFromJobId' as evidence_reused_from_job_id,
                       j.input_snapshot ->> 'commitSha' as source_commit,
                       r.analysis_job_id as result_job_id,
                       r.inference_metadata -> 'facts' -> 'sourceSummary' ->> 'commitSha' as facts_commit,
                       r.inference_metadata -> 'facts' -> 'sourceSummary' -> 'promptCoverage' ->> 'truncated' as prompt_truncated,
                       r.inference_metadata -> 'facts' -> 'sourceSummary' -> 'scanMetrics' ->> 'truncated' as scan_truncated,
                       r.inference_metadata -> 'facts' -> 'databaseSummary' ->> 'truncated' as database_truncated,
                       (r.inference_metadata -> 'facts' -> 'databaseSummary' -> 'warnings') = '[]'::jsonb as database_warnings_empty,
                       jsonb_typeof(r.inference_metadata -> 'facts' -> 'relations') as relations_type,
                       (j.status = 'COMPLETED'
                         and (j.input_snapshot ->> 'commitSha') is not null
                         and (j.input_snapshot ->> 'commitSha') =
                             (r.inference_metadata -> 'facts' -> 'sourceSummary' ->> 'commitSha')
                         and (r.inference_metadata -> 'facts' -> 'sourceSummary' -> 'promptCoverage' ->> 'truncated') = 'false'
                         and (r.inference_metadata -> 'facts' -> 'sourceSummary' -> 'scanMetrics' ->> 'truncated') = 'false'
                         and (r.inference_metadata -> 'facts' -> 'databaseSummary' ->> 'truncated') = 'false'
                         and (r.inference_metadata -> 'facts' -> 'databaseSummary' -> 'warnings') = '[]'::jsonb
                         and jsonb_typeof(r.inference_metadata -> 'facts' -> 'relations') = 'array'
                       ) as reusable_evidence,
                       j.created_at, j.completed_at
                from public.analysis_jobs j
                join public.projects p on p.id = j.project_id
                left join public.requirement_versions rv on rv.id = j.requirement_version_id
                left join public.analysis_results r on r.analysis_job_id = j.id
                where j.project_id in (:projectIds)
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
                rs.getString("summary"), rs.getBoolean("approved"),
                reused == null ? null : UUID.fromString(reused),
                reusable, reusable ? List.of() : evidenceUnavailableReasons(rs), rs.getString("source_commit"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class));
    }

    /** SQL의 재사용 조건별 결손 사유를 코드로 돌려준다. 화면에서는 이 코드를 사용자 언어로 표시한다. */
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
        if (sourceCommit == null) reasons.add("SOURCE_COMMIT_MISSING");
        if (factsCommit == null) reasons.add("SAVED_COMMIT_MISSING");
        if (sourceCommit != null && factsCommit != null && !sourceCommit.equals(factsCommit)) {
            reasons.add("SOURCE_COMMIT_MISMATCH");
        }
        addTruncationReason(reasons, rs.getString("prompt_truncated"), "SOURCE_PROMPT_COVERAGE_MISSING", "SOURCE_PROMPT_TRUNCATED");
        addTruncationReason(reasons, rs.getString("scan_truncated"), "SOURCE_SCAN_STATUS_MISSING", "SOURCE_SCAN_TRUNCATED");
        addTruncationReason(reasons, rs.getString("database_truncated"), "DATABASE_COVERAGE_MISSING", "DATABASE_TRUNCATED");
        Boolean warningsEmpty = rs.getObject("database_warnings_empty", Boolean.class);
        if (warningsEmpty == null) reasons.add("DATABASE_WARNINGS_MISSING");
        else if (!warningsEmpty) reasons.add("DATABASE_WARNINGS_PRESENT");
        if (!"array".equals(rs.getString("relations_type"))) reasons.add("RELATIONS_MISSING");
        if (reasons.isEmpty()) reasons.add("EVIDENCE_FORMAT_UNSUPPORTED");
        return reasons;
    }

    private static void addTruncationReason(List<String> reasons, String value, String missingCode, String truncatedCode) {
        if (value == null) reasons.add(missingCode);
        else if (!"false".equals(value)) reasons.add(truncatedCode);
    }
}
