package com.doenaai.analysis.repository;

import com.doenaai.analysis.dto.AnalysisResultResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.Map;
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
}
