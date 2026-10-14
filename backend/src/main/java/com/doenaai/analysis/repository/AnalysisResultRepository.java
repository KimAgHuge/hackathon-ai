package com.doenaai.analysis.repository;

import com.doenaai.analysis.dto.AnalysisResultResponse;
import com.doenaai.analysis.dto.ReusableEvidence;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class AnalysisResultRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    /** jobId의 상태와 result·AI에 실제 전달된 facts를 한 트랜잭션에 저장한다. */
    @Transactional
    public void complete(UUID jobId, Map<String, Object> result, Map<String, Object> facts) {
        int changed = jdbcTemplate.update("""
                update public.analysis_jobs set status = 'COMPLETED', source_progress = 100,
                    database_progress = 100, relation_progress = 100,
                    completed_at = now(), updated_at = now(), error_message = null
                where id = ? and status = 'RUNNING'
                """, jobId);
        if (changed != 1) throw new IllegalStateException("Analysis job is no longer running.");
        jdbcTemplate.update("""
                insert into public.analysis_results
                    (analysis_job_id, original_result, risk_level, summary, inference_metadata)
                values (?, cast(? as jsonb), ?, ?, cast(? as jsonb))
                """, jobId, objectMapper.writeValueAsString(result), result.get("riskLevel"),
                result.get("summary"), objectMapper.writeValueAsString(Map.of("facts", facts)));
    }

    /** 완료된 jobId의 분석 결과와 당시 입력 근거를 조회한다; 없으면 Optional.empty(). */
    public Optional<AnalysisResultResponse> findByJobId(UUID jobId) {
        return jdbcTemplate.query("""
                select r.analysis_job_id, j.project_id, r.original_result, r.inference_metadata, r.created_at
                from public.analysis_results r join public.analysis_jobs j on j.id = r.analysis_job_id
                where r.analysis_job_id = ? and j.status = 'COMPLETED'
                """, (rs, row) -> {
            Map<String, Object> result = objectMapper.readValue(rs.getString("original_result"), MAP);
            var metadata = objectMapper.readTree(rs.getString("inference_metadata"));
            Map<String, Object> facts = metadata.has("facts")
                    ? objectMapper.convertValue(metadata.get("facts"), MAP) : Map.of();
            return new AnalysisResultResponse(jobId, rs.getObject("project_id", UUID.class),
                    (String) result.get("summary"), (String) result.get("riskLevel"),
                    ((Number) result.getOrDefault("riskScore", 0)).intValue(), result, facts,
                    rs.getObject("created_at", OffsetDateTime.class));
        }, jobId).stream().findFirst();
    }

    /** 같은 프로젝트·고정 Git SHA의 완전한 이전 입력 근거를 찾는다. */
    @SuppressWarnings("unchecked")
    public Optional<ReusableEvidence> findReusableEvidence(UUID projectId, String commitSha) {
        return jdbcTemplate.query("""
                select j.id, r.inference_metadata -> 'facts' as facts
                from public.analysis_jobs j join public.analysis_results r on r.analysis_job_id = j.id
                where j.project_id = ? and j.status = 'COMPLETED'
                  and j.input_snapshot ->> 'commitSha' = ?
                  and (r.inference_metadata -> 'facts' -> 'sourceSummary'
                      -> 'promptCoverage' ->> 'truncated') = 'false'
                  and (r.inference_metadata -> 'facts' -> 'sourceSummary'
                      -> 'scanMetrics' ->> 'truncated') = 'false'
                  and (r.inference_metadata -> 'facts' -> 'databaseSummary' ->> 'truncated') = 'false'
                  and (r.inference_metadata -> 'facts' -> 'databaseSummary' -> 'warnings') = '[]'::jsonb
                  and jsonb_typeof(r.inference_metadata -> 'facts' -> 'relations') = 'array'
                order by j.created_at desc, j.id desc limit 1
                """, (rs, row) -> {
            Map<String, Object> facts = objectMapper.readValue(rs.getString("facts"), MAP);
            return new ReusableEvidence(rs.getObject("id", UUID.class),
                    (Map<String, Object>) facts.get("sourceSummary"),
                    (Map<String, Object>) facts.get("databaseSummary"),
                    (List<Map<String, Object>>) facts.get("relations"));
        }, projectId, commitSha).stream().findFirst();
    }

    /** 사용자가 명시적으로 선택한 완료 작업의 완전한 입력 근거만 반환한다. */
    @SuppressWarnings("unchecked")
    public Optional<ReusableEvidence> findReusableEvidenceByJobId(UUID projectId, UUID sourceJobId) {
        return jdbcTemplate.query("""
                select j.id, r.inference_metadata -> 'facts' as facts
                from public.analysis_jobs j join public.analysis_results r on r.analysis_job_id = j.id
                where j.id = ? and j.project_id = ? and j.status = 'COMPLETED'
                  and (j.input_snapshot ->> 'commitSha') is not null
                  and (j.input_snapshot ->> 'commitSha') =
                      (r.inference_metadata -> 'facts' -> 'sourceSummary' ->> 'commitSha')
                  and (r.inference_metadata -> 'facts' -> 'sourceSummary'
                      -> 'promptCoverage' ->> 'truncated') = 'false'
                  and (r.inference_metadata -> 'facts' -> 'sourceSummary'
                      -> 'scanMetrics' ->> 'truncated') = 'false'
                  and (r.inference_metadata -> 'facts' -> 'databaseSummary' ->> 'truncated') = 'false'
                  and (r.inference_metadata -> 'facts' -> 'databaseSummary' -> 'warnings') = '[]'::jsonb
                  and jsonb_typeof(r.inference_metadata -> 'facts' -> 'relations') = 'array'
                """, (rs, row) -> {
            Map<String, Object> facts = objectMapper.readValue(rs.getString("facts"), MAP);
            return new ReusableEvidence(rs.getObject("id", UUID.class),
                    (Map<String, Object>) facts.get("sourceSummary"),
                    (Map<String, Object>) facts.get("databaseSummary"),
                    (List<Map<String, Object>>) facts.get("relations"));
        }, sourceJobId, projectId).stream().findFirst();
    }
}
