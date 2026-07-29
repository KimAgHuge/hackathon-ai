package com.doenaai.analysis.repository;

import com.doenaai.analysis.dto.AnalysisJobResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class AnalysisJobRepository {

    private final JdbcTemplate jdbcTemplate;

    public AnalysisJobResponse createProjectScan(
            UUID projectId,
            UUID requestedBy,
            String repositoryUrl,
            String branch,
            String commitSha
    ) {
        return jdbcTemplate.queryForObject(
                """
                insert into public.analysis_jobs (
                    project_id,
                    requested_by,
                    analysis_type,
                    status,
                    input_snapshot
                )
                values (
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
                    status,
                    source_progress,
                    database_progress,
                    relation_progress,
                    created_at
                """,
                this::mapRow,
                projectId,
                requestedBy,
                repositoryUrl,
                branch,
                commitSha
        );
    }

    public Optional<AnalysisJobResponse> findById(
            UUID analysisJobId
    ) {
        return jdbcTemplate.query(
                """
                select
                    id,
                    project_id,
                    status,
                    source_progress,
                    database_progress,
                    relation_progress,
                    created_at
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
        return AnalysisJobResponse.of(
                resultSet.getObject(
                        "id",
                        UUID.class
                ),
                resultSet.getObject(
                        "project_id",
                        UUID.class
                ),
                resultSet.getString("status"),
                resultSet.getInt("source_progress"),
                resultSet.getInt("database_progress"),
                resultSet.getInt("relation_progress"),
                resultSet.getObject(
                        "created_at",
                        java.time.OffsetDateTime.class
                )
        );
    }
}
