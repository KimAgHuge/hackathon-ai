package com.doenaai.analysis.repository;

import com.doenaai.analysis.AnalysisNodeIdentity;
import com.doenaai.analysis.dto.AnalysisJobResponse;
import com.doenaai.analysis.dto.AnalysisActivityEvent;
import com.doenaai.analysis.dto.AnalysisWorkItem;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class AnalysisJobRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AnalysisNodeIdentity nodeIdentity;
    private static final TypeReference<List<AnalysisActivityEvent>> ACTIVITY_LOG = new TypeReference<>() {};

    /** 승인 사례의 보완 근거를 읽을 때 당시 저장소와 커밋만 사용한다. */
    public Optional<TrainingSource> findTrainingSource(UUID jobId) {
        return jdbcTemplate.query("""
                select input_snapshot ->> 'repositoryUrl' as url, input_snapshot ->> 'branch' as branch,
                       input_snapshot ->> 'commitSha' as revision
                from public.analysis_jobs where id = ? and status = 'COMPLETED'
                """, (rs, row) -> new TrainingSource(rs.getString("url"), rs.getString("branch"),
                rs.getString("revision")), jobId).stream().findFirst();
    }

    public record TrainingSource(String url, String branch, String revision) {}

    /** 프로젝트·요구사항 버전·Git 커밋을 고정한 PENDING 작업을 생성해 반환한다. */
    public AnalysisJobResponse createProjectScan(
            UUID projectId,
            UUID requirementVersionId,
            UUID requestedBy,
            String repositoryUrl,
            String branch,
            String commitSha
    ) {
        return jdbcTemplate.queryForObject(
                """
                insert into public.analysis_jobs (
                    project_id,
                    requirement_version_id,
                    requested_by,
                    analysis_type,
                    status,
                    target_worker_id,
                    input_snapshot
                )
                values (
                    ?,
                    ?,
                    ?,
                    'PROJECT_SCAN_V2',
                    'PENDING',
                    ?,
                    jsonb_build_object(
                        'sourceType', 'GIT',
                        'repositoryUrl', ?,
                        'branch', ?,
                        'commitSha', ?
                    )
                )
                returning
                    id,
                    project_id,
                    requirement_version_id,
                    status,
                    source_progress,
                    database_progress,
                    relation_progress,
                    created_at, error_message, started_at, completed_at,
                    analysis_options -> 'activityLog' as activity_log
                """,
                this::mapRow,
                projectId,
                requirementVersionId,
                requestedBy,
                nodeIdentity.instanceId(),
                repositoryUrl,
                branch,
                commitSha
        );
    }

    /** 근거 전달 방식(ALWAYS·OFF)을 작업을 만드는 같은 SQL 안에서 옵션에 저장한다. 워커가 선점하기 전에 항상 반영된다. */
    public AnalysisJobResponse createProjectScan(
            UUID projectId, UUID requirementVersionId, UUID requestedBy,
            String repositoryUrl, String branch, String commitSha, String evidenceSelection
    ) {
        return jdbcTemplate.queryForObject(
                """
                insert into public.analysis_jobs (
                    project_id, requirement_version_id, requested_by, analysis_type, status,
                    target_worker_id, input_snapshot, analysis_options
                )
                values (
                    ?, ?, ?, 'PROJECT_SCAN_V2', 'PENDING', ?,
                    jsonb_build_object('sourceType', 'GIT', 'repositoryUrl', ?, 'branch', ?, 'commitSha', ?),
                    jsonb_build_object('evidenceSelection', ?::text)
                )
                returning
                    id, project_id, requirement_version_id, status,
                    source_progress, database_progress, relation_progress,
                    created_at, error_message, started_at, completed_at,
                    analysis_options -> 'activityLog' as activity_log
                """,
                this::mapRow,
                projectId, requirementVersionId, requestedBy, nodeIdentity.instanceId(),
                repositoryUrl, branch, commitSha, evidenceSelection
        );
    }

    /** 기존 완료 작업의 고정 입력 스냅샷으로 새 PENDING 분석을 생성한다. 원격 Git/DB는 조회하지 않는다. */
    public AnalysisJobResponse createFromSavedEvidence(UUID projectId, UUID requirementVersionId,
                                                       UUID requestedBy, UUID sourceJobId) {
        return createFromSavedEvidence(projectId, requirementVersionId, requestedBy, sourceJobId, null);
    }

    /** 저장 근거 작업을 만들면서 근거 전달 방식(ALWAYS·OFF)도 함께 저장한다. null이면 기본 동작이다. */
    public AnalysisJobResponse createFromSavedEvidence(UUID projectId, UUID requirementVersionId,
                                                       UUID requestedBy, UUID sourceJobId, String evidenceSelection) {
        return jdbcTemplate.queryForObject("""
                insert into public.analysis_jobs (
                    project_id, requirement_version_id, requested_by, analysis_type, status,
                    target_worker_id,
                    input_snapshot, analysis_options
                )
                select j.project_id, ?, ?, 'PROJECT_SCAN_V2', 'PENDING', ?, j.input_snapshot,
                       jsonb_build_object('reuseMode', 'STORED', 'userConfirmedUnchanged', true,
                                          'reuseFromJobId', j.id::text,
                                          'evidenceReusedFromJobId', j.id::text)
                       || case when ?::text is null then '{}'::jsonb
                               else jsonb_build_object('evidenceSelection', ?::text) end
                from public.analysis_jobs j
                where j.id = ? and j.project_id = ? and j.status = 'COMPLETED'
                returning id, project_id, requirement_version_id, status,
                    source_progress, database_progress, relation_progress,
                    created_at, error_message, started_at, completed_at,
                    analysis_options -> 'activityLog' as activity_log
                """, this::mapRow, requirementVersionId, requestedBy, nodeIdentity.instanceId(),
                evidenceSelection, evidenceSelection, sourceJobId, projectId);
    }

    public Optional<AnalysisJobResponse> findById(
            UUID analysisJobId
    ) {
        return jdbcTemplate.query(
                """
                select
                    id,
                    project_id,
                    requirement_version_id,
                    status,
                    source_progress,
                    database_progress,
                    relation_progress,
                    created_at, error_message, started_at, completed_at,
                    analysis_options -> 'activityLog' as activity_log
                from public.analysis_jobs
                where id = ?
                """,
                this::mapRow,
                analysisJobId
        ).stream().findFirst();
    }

    private AnalysisJobResponse mapRow(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new AnalysisJobResponse(
                resultSet.getObject(
                        "id",
                        UUID.class
                ),
                resultSet.getObject(
                        "project_id",
                        UUID.class
                ),
                resultSet.getObject(
                        "requirement_version_id",
                        UUID.class
                ),
                resultSet.getString("status"),
                resultSet.getInt("source_progress"),
                resultSet.getInt("database_progress"),
                resultSet.getInt("relation_progress"),
                (resultSet.getInt("source_progress") + resultSet.getInt("database_progress")
                        + resultSet.getInt("relation_progress")) / 3,
                resultSet.getObject(
                        "created_at",
                        java.time.OffsetDateTime.class
                ),
                resultSet.getString("error_message"),
                resultSet.getObject("started_at", java.time.OffsetDateTime.class),
                resultSet.getObject("completed_at", java.time.OffsetDateTime.class),
                resultSet.getString("activity_log") == null
                        ? List.of() : objectMapper.readValue(resultSet.getString("activity_log"), ACTIVITY_LOG)
        );
    }

    /** 요청을 받은 백엔드의 작업만 선점한다. 원격 호출 전에 선점 결과를 저장한다. */
    @Transactional
    public Optional<AnalysisWorkItem> claimNext() {
        return jdbcTemplate.query("""
                with candidate as (
                    select id from public.analysis_jobs
                    where status = 'PENDING' and analysis_type = 'PROJECT_SCAN_V2'
                      and target_worker_id = ?
                    order by created_at, id
                    for update skip locked limit 1
                )
                update public.analysis_jobs j
                set status = 'RUNNING', started_at = now(), updated_at = now(),
                    error_message = null, claimed_by_worker_id = ?
                from candidate c where j.id = c.id
                returning j.id, j.project_id, j.requirement_version_id, j.requested_by,
                    j.input_snapshot ->> 'repositoryUrl' as repository_url,
                    j.input_snapshot ->> 'branch' as branch,
                    j.input_snapshot ->> 'commitSha' as commit_sha,
                    j.analysis_options ->> 'reuseFromJobId' as reuse_from_job_id,
                    j.analysis_options ->> 'evidenceSelection' as evidence_selection
                """, (rs, row) -> new AnalysisWorkItem(
                rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("requirement_version_id", UUID.class), rs.getObject("requested_by", UUID.class),
                rs.getString("repository_url"), rs.getString("branch"), rs.getString("commit_sha"),
                rs.getString("reuse_from_job_id") == null ? null : UUID.fromString(rs.getString("reuse_from_job_id")),
                rs.getString("evidence_selection")
        ), nodeIdentity.instanceId(), nodeIdentity.instanceId()).stream().findFirst();
    }

    /** RUNNING 작업의 세 수집 단계 진행률(0~100)을 갱신한다. */
    public void progress(UUID jobId, int source, int database, int relation) {
        int changed = jdbcTemplate.update("""
                update public.analysis_jobs
                set source_progress = ?, database_progress = ?, relation_progress = ?, updated_at = now()
                where id = ? and status = 'RUNNING'
                """, source, database, relation, jobId);
        if (changed != 1) throw new IllegalStateException("Analysis job is no longer running.");
    }

    /** analysis_options에 고정된 공개용 단계 문구만 추가한다. 기존 DB 구조 변경은 필요하지 않다. */
    public void appendActivity(UUID jobId, String label, String state) {
        jdbcTemplate.update("""
                update public.analysis_jobs
                set analysis_options = jsonb_set(analysis_options, '{activityLog}',
                    coalesce(analysis_options -> 'activityLog', '[]'::jsonb)
                    || jsonb_build_array(jsonb_build_object('at', now(), 'label', ?, 'state', ?)), true),
                    updated_at = now()
                where id = ? and status = 'RUNNING'
                """, label, state, jobId);
    }

    /** 재사용한 근거의 출처를 남겨 새 분석 결과의 계보를 추적한다. */
    public void markEvidenceReused(UUID jobId, UUID sourceJobId) {
        int changed = jdbcTemplate.update("""
                update public.analysis_jobs
                set analysis_options = jsonb_set(analysis_options, '{evidenceReusedFromJobId}', to_jsonb(?::text)),
                    updated_at = now()
                where id = ? and status = 'RUNNING'
                """, sourceJobId.toString(), jobId);
        if (changed != 1) throw new IllegalStateException("Analysis job is no longer running.");
    }

    /** RUNNING 작업을 FAILED로 끝낸다; safeMessage에는 자격증명·원본 예외를 넣지 않는다. */
    public void fail(UUID jobId, String safeMessage) {
        jdbcTemplate.update("""
                update public.analysis_jobs set status = 'FAILED', error_message = ?,
                    completed_at = now(), updated_at = now(),
                    analysis_options = jsonb_set(analysis_options, '{activityLog}',
                        coalesce(analysis_options -> 'activityLog', '[]'::jsonb)
                        || jsonb_build_array(jsonb_build_object('at', now(), 'label',
                            '분석을 완료하지 못했습니다.', 'state', 'FAILED')), true)
                where id = ? and status = 'RUNNING'
                """, safeMessage, jobId);
    }

    /** 대기·실행 중 작업만 취소한다. 완료 결과는 변경하지 않는다. */
    public boolean cancel(UUID jobId) {
        return jdbcTemplate.update("""
                update public.analysis_jobs set status = 'CANCELLED',
                    error_message = null, completed_at = now(), updated_at = now(),
                    analysis_options = jsonb_set(analysis_options, '{activityLog}',
                        coalesce(analysis_options -> 'activityLog', '[]'::jsonb)
                        || jsonb_build_array(jsonb_build_object('at', now(), 'label',
                            '분석이 취소되었습니다.', 'state', 'CANCELLED')), true)
                where id = ? and status in ('PENDING', 'RUNNING')
                """, jobId) == 1;
    }

    public boolean isCancelled(UUID jobId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                select status = 'CANCELLED' from public.analysis_jobs where id = ?
                """, Boolean.class, jobId));
    }

    /** 현재 버전에 진행 중이거나 완료된 분석이 있으면 이전 결과의 질문을 다시 이어 쓰지 않는다. */
    public boolean hasActiveOrCompletedAnalysis(UUID requirementVersionId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                select exists (
                    select 1 from public.analysis_jobs
                    where requirement_version_id = ?
                      and analysis_type in ('PROJECT_SCAN', 'PROJECT_SCAN_V2')
                      and status in ('PENDING', 'RUNNING', 'COMPLETED')
                )
                """, Boolean.class, requirementVersionId));
    }

    /** 중단된 실행 작업은 다른 서버에서 재처리하지 않고 제한 시간 후 실패로 끝낸다. */
    public void failStale(int timeoutSeconds) {
        jdbcTemplate.update("""
                update public.analysis_jobs set status = 'FAILED',
                    error_message = '분석 처리 시간이 초과되었거나 서버가 중단되었습니다. 새 분석을 시작하세요.',
                    completed_at = now(), updated_at = now(),
                    analysis_options = jsonb_set(analysis_options, '{activityLog}',
                        coalesce(analysis_options -> 'activityLog', '[]'::jsonb)
                        || jsonb_build_array(jsonb_build_object('at', now(), 'label',
                            '분석 처리 시간이 초과되었거나 서버가 중단되었습니다.', 'state', 'FAILED')), true)
                where status = 'RUNNING' and analysis_type = 'PROJECT_SCAN_V2'
                  and updated_at < now() - (? * interval '1 second')
                """, timeoutSeconds);
    }
}
