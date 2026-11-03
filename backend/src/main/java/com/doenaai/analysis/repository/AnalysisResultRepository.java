package com.doenaai.analysis.repository;

import com.doenaai.analysis.SourceEvidenceVersion;
import com.doenaai.analysis.dto.AnalysisResultResponse;
import com.doenaai.analysis.dto.AnalysisReviewDecision;
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

    /** 수동 선택의 필수 구조와 커밋 조건을 목록과 실제 조회에 동일하게 적용한다. */
    static final String SAVED_EVIDENCE_CONDITION = """
            j.status = 'COMPLETED'
            and jsonb_typeof(r.inference_metadata -> 'facts' -> 'sourceSummary') = 'object'
            and jsonb_typeof(r.inference_metadata -> 'facts' -> 'databaseSummary') = 'object'
            and jsonb_typeof(r.inference_metadata -> 'facts' -> 'sourceSummary' -> 'components') = 'array'
            and jsonb_typeof(r.inference_metadata -> 'facts' -> 'databaseSummary' -> 'tables') = 'array'
            and jsonb_typeof(r.inference_metadata -> 'facts' -> 'relations') = 'array'
            and nullif(btrim(j.input_snapshot ->> 'commitSha'), '') is not null
            and jsonb_typeof(r.inference_metadata -> 'facts' -> 'sourceSummary' -> 'commitSha') = 'string'
            and (j.input_snapshot ->> 'commitSha') = (r.inference_metadata -> 'facts' -> 'sourceSummary' ->> 'commitSha')
            and not exists (select 1 from jsonb_array_elements(
                case when jsonb_typeof(r.inference_metadata -> 'facts' -> 'sourceSummary' -> 'components') = 'array'
                then r.inference_metadata -> 'facts' -> 'sourceSummary' -> 'components' else '[]'::jsonb end) item
                where jsonb_typeof(item) is distinct from 'object')
            and not exists (select 1 from jsonb_array_elements(
                case when jsonb_typeof(r.inference_metadata -> 'facts' -> 'databaseSummary' -> 'tables') = 'array'
                then r.inference_metadata -> 'facts' -> 'databaseSummary' -> 'tables' else '[]'::jsonb end) item
                where jsonb_typeof(item) is distinct from 'object')
            and not exists (select 1 from jsonb_array_elements(
                case when jsonb_typeof(r.inference_metadata -> 'facts' -> 'relations') = 'array'
                then r.inference_metadata -> 'facts' -> 'relations' else '[]'::jsonb end) item
                where jsonb_typeof(item) is distinct from 'object')
            """;

    /** jobId의 상태와 result·AI에 실제 전달된 facts를 한 트랜잭션에 저장한다. */
    @Transactional
    public void complete(UUID jobId, Map<String, Object> result, Map<String, Object> facts) {
        int changed = jdbcTemplate.update("""
                update public.analysis_jobs set status = 'COMPLETED', source_progress = 100,
                    database_progress = 100, relation_progress = 100,
                    completed_at = now(), updated_at = now(), error_message = null,
                    analysis_options = jsonb_set(analysis_options, '{activityLog}',
                        coalesce(analysis_options -> 'activityLog', '[]'::jsonb)
                        || jsonb_build_array(jsonb_build_object('at', now(), 'label',
                            '분석 결과 저장', 'state', 'COMPLETED')), true)
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
                select r.analysis_job_id, j.project_id, r.original_result, r.corrected_result,
                       r.inference_metadata, r.created_at, r.review_status, r.review_note,
                       r.reviewed_by, r.reviewed_at, r.summary, r.risk_level
                from public.analysis_results r join public.analysis_jobs j on j.id = r.analysis_job_id
                where r.analysis_job_id = ? and j.status = 'COMPLETED'
                """, (rs, row) -> {
            Map<String, Object> result = objectMapper.readValue(rs.getString("original_result"), MAP);
            String correctedJson = rs.getString("corrected_result");
            Map<String, Object> corrected = correctedJson == null ? null : objectMapper.readValue(correctedJson, MAP);
            Map<String, Object> effective = corrected == null ? result : corrected;
            var metadata = objectMapper.readTree(rs.getString("inference_metadata"));
            Map<String, Object> facts = metadata.has("facts")
                    ? objectMapper.convertValue(metadata.get("facts"), MAP) : Map.of();
            return new AnalysisResultResponse(jobId, rs.getObject("project_id", UUID.class),
                    rs.getString("summary"), rs.getString("risk_level"),
                    ((Number) effective.getOrDefault("riskScore", 0)).intValue(), result, facts,
                    rs.getObject("created_at", OffsetDateTime.class), rs.getString("review_status"),
                    corrected, rs.getString("review_note"), rs.getObject("reviewed_by", UUID.class),
                    rs.getObject("reviewed_at", OffsetDateTime.class));
        }, jobId).stream().findFirst();
    }

    /** 대기 중인 검토만 갱신해 중복 승인·반려 요청의 덮어쓰기를 막는다. */
    public boolean review(UUID jobId, UUID reviewerId, AnalysisReviewDecision decision,
                          String note, Map<String, Object> correctedResult) {
        boolean approved = decision == AnalysisReviewDecision.APPROVED;
        String correctedJson = correctedResult == null ? null : objectMapper.writeValueAsString(correctedResult);
        int changed = jdbcTemplate.update("""
                update public.analysis_results r
                set review_status = ?, approved = ?, reviewed_by = ?, reviewed_at = now(),
                    approved_by = case when ? then ? else null end,
                    approved_at = case when ? then now() else null end,
                    review_note = ?, corrected_result = cast(? as jsonb),
                    summary = case when ? then ? else summary end,
                    risk_level = case when ? then ? else risk_level end,
                    updated_at = now()
                where r.analysis_job_id = ? and r.review_status = 'PENDING'
                  and exists (select 1 from public.analysis_jobs j
                              where j.id = r.analysis_job_id and j.status = 'COMPLETED')
                """, decision.name(), approved, reviewerId,
                approved, reviewerId, approved, note, correctedJson,
                correctedResult != null, correctedResult == null ? null : correctedResult.get("summary"),
                correctedResult != null, correctedResult == null ? null : correctedResult.get("riskLevel"),
                jobId);
        return changed == 1;
    }

    /** 같은 프로젝트·고정 Git SHA의 완전한 이전 입력 근거를 찾는다. */
    @SuppressWarnings("unchecked")
    public Optional<ReusableEvidence> findReusableEvidence(UUID projectId, String commitSha) {
        return jdbcTemplate.query("""
                select j.id, r.inference_metadata -> 'facts' as facts
                from public.analysis_jobs j join public.analysis_results r on r.analysis_job_id = j.id
                where j.project_id = ? and j.status = 'COMPLETED'
                  and j.input_snapshot ->> 'commitSha' = ?
                  and (r.inference_metadata -> 'facts' -> 'sourceSummary' ->> 'collectorVersion') = ?
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
        }, projectId, commitSha, SourceEvidenceVersion.CURRENT).stream().findFirst();
    }

    /** 사용자가 고른 완료 작업의 유효 입력을 반환하고 수집 범위의 한계는 그대로 유지한다. */
    @SuppressWarnings("unchecked")
    public Optional<ReusableEvidence> findReusableEvidenceByJobId(UUID projectId, UUID sourceJobId) {
        return jdbcTemplate.query("""
                select j.id, r.inference_metadata -> 'facts' as facts
                from public.analysis_jobs j join public.analysis_results r on r.analysis_job_id = j.id
                where j.id = ? and j.project_id = ? and
                """ + SAVED_EVIDENCE_CONDITION, (rs, row) -> {
            Map<String, Object> facts = objectMapper.readValue(rs.getString("facts"), MAP);
            return new ReusableEvidence(rs.getObject("id", UUID.class),
                    (Map<String, Object>) facts.get("sourceSummary"),
                    (Map<String, Object>) facts.get("databaseSummary"),
                    (List<Map<String, Object>>) facts.get("relations"));
        }, sourceJobId, projectId).stream().findFirst();
    }
}
