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
        return new AnalysisHistoryItem(
                rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getString("project_name"), rs.getObject("requirement_version_id", UUID.class),
                rs.getString("requirement_title"), rs.getString("status"), rs.getString("risk_level"),
                rs.getString("summary"), rs.getBoolean("approved"),
                reused == null ? null : UUID.fromString(reused),
                rs.getBoolean("reusable_evidence"), rs.getString("source_commit"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class));
    }
}
