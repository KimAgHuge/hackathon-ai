package com.doenaai.analysis.repository;

import com.doenaai.analysis.dto.AnalysisJobResponse;
import com.doenaai.analysis.dto.AnalysisWorkItem;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class AnalysisJobRepository {

    private final JdbcTemplate jdbcTemplate;

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
                    input_snapshot
                )
                values (
                    ?,
                    ?,
                    ?,
                    'PROJECT_SCAN',
                    'PENDING',
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
                    created_at, error_message, started_at, completed_at
                """,
                this::mapRow,
                projectId,
                requirementVersionId,
                requestedBy,
                repositoryUrl,
                branch,
                commitSha
        );
    }

    /** 기존 완료 작업의 고정 입력 스냅샷으로 새 PENDING 분석을 생성한다. 원격 Git/DB는 조회하지 않는다. */
    public AnalysisJobResponse createFromSavedEvidence(UUID projectId, UUID requirementVersionId,
                                                       UUID requestedBy, UUID sourceJobId) {
        return jdbcTemplate.queryForObject("""
                insert into public.analysis_jobs (
                    project_id, requirement_version_id, requested_by, analysis_type, status,
                    input_snapshot, analysis_options
                )
                select j.project_id, ?, ?, 'PROJECT_SCAN', 'PENDING', j.input_snapshot,
                       jsonb_build_object('reuseMode', 'STORED', 'userConfirmedUnchanged', true,
                                          'reuseFromJobId', j.id::text,
                                          'evidenceReusedFromJobId', j.id::text)
                from public.analysis_jobs j
                where j.id = ? and j.project_id = ? and j.status = 'COMPLETED'
                returning id, project_id, requirement_version_id, status,
                    source_progress, database_progress, relation_progress,
                    created_at, error_message, started_at, completed_at
                """, this::mapRow, requirementVersionId, requestedBy, sourceJobId, projectId);
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
                    created_at, error_message, started_at, completed_at
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
                resultSet.getObject("completed_at", java.time.OffsetDateTime.class)
        );
    }

    /** Claim and commit before any remote IO; competing workers cannot claim the same row. */
    @Transactional
    public Optional<AnalysisWorkItem> claimNext() {
        return jdbcTemplate.query("""
                with candidate as (
                    select id from public.analysis_jobs
                    where status = 'PENDING' and analysis_type = 'PROJECT_SCAN'
                    order by created_at, id
                    for update skip locked limit 1
                )
                update public.analysis_jobs j
                set status = 'RUNNING', started_at = now(), updated_at = now(),
                    error_message = null
                from candidate c where j.id = c.id
                returning j.id, j.project_id, j.requirement_version_id, j.requested_by,
                    j.input_snapshot ->> 'repositoryUrl' as repository_url,
                    j.input_snapshot ->> 'branch' as branch,
                    j.input_snapshot ->> 'commitSha' as commit_sha,
                    j.analysis_options ->> 'reuseFromJobId' as reuse_from_job_id
                """, (rs, row) -> new AnalysisWorkItem(
                rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("requirement_version_id", UUID.class), rs.getObject("requested_by", UUID.class),
                rs.getString("repository_url"), rs.getString("branch"), rs.getString("commit_sha"),
                rs.getString("reuse_from_job_id") == null ? null : UUID.fromString(rs.getString("reuse_from_job_id"))
        )).stream().findFirst();
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
                    completed_at = now(), updated_at = now()
                where id = ? and status = 'RUNNING'
                """, safeMessage, jobId);
    }

    /** 대기·실행 중 작업만 취소한다. 완료 결과는 변경하지 않는다. */
    public boolean cancel(UUID jobId) {
        return jdbcTemplate.update("""
                update public.analysis_jobs set status = 'CANCELLED',
                    error_message = null, completed_at = now(), updated_at = now()
                where id = ? and status in ('PENDING', 'RUNNING')
                """, jobId) == 1;
    }

    public boolean isCancelled(UUID jobId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                select status = 'CANCELLED' from public.analysis_jobs where id = ?
                """, Boolean.class, jobId));
    }

    /** Recover abandoned work after a process crash; never retry a remote call automatically. */
    public void failStale(int timeoutSeconds) {
        jdbcTemplate.update("""
                update public.analysis_jobs set status = 'FAILED',
                    error_message = '분석 처리 시간이 초과되었거나 서버가 중단되었습니다. 새 분석을 시작하세요.',
                    completed_at = now(), updated_at = now()
                where status = 'RUNNING' and analysis_type = 'PROJECT_SCAN'
                  and updated_at < now() - (? * interval '1 second')
                """, timeoutSeconds);
    }
}
