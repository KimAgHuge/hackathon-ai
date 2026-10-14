package com.doenaai.requirement.repository;

import com.doenaai.requirement.dto.RequirementVersionCreateRequest;
import com.doenaai.requirement.entity.RequirementVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class RequirementVersionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public void lockRequirement(UUID requirementId) {
        jdbcTemplate.queryForObject(
                """
                select id
                from public.requirements
                where id = ?
                for update
                """,
                UUID.class,
                requirementId
        );
    }

    public int findNextVersionNumber(UUID requirementId) {
        Integer nextVersion = jdbcTemplate.queryForObject(
                """
                select coalesce(max(version_number), 0) + 1
                from public.requirement_versions
                where requirement_id = ?
                """,
                Integer.class,
                requirementId
        );

        if (nextVersion == null) {
            throw internalError("다음 요구사항 버전 번호를 계산하지 못했습니다.");
        }

        return nextVersion;
    }

    public void markCurrentVersionHistorical(UUID requirementId) {
        jdbcTemplate.update(
                """
                update public.requirement_versions
                set is_current = false
                where requirement_id = ?
                  and is_current = true
                """,
                requirementId
        );
    }

    public RequirementVersion insert(
            UUID requirementId,
            int versionNumber,
            RequirementVersionCreateRequest request,
            UUID userId
    ) {
        return insert(requirementId, versionNumber, request, userId, List.of(), null, null);
    }

    public RequirementVersion insert(
            UUID requirementId, int versionNumber, RequirementVersionCreateRequest request,
            UUID userId, UUID parentVersionId
    ) {
        return insert(requirementId, versionNumber, request, userId, List.of(), null, parentVersionId);
    }

    public RequirementVersion insert(
            UUID requirementId, int versionNumber, RequirementVersionCreateRequest request,
            UUID userId, List<Object> clarifications, UUID sourceAnalysisJobId, UUID parentVersionId
    ) {
        return jdbcTemplate.queryForObject(
                """
                insert into public.requirement_versions (
                    requirement_id,
                    version_number,
                    title,
                    original_content,
                    refined_content,
                    acceptance_criteria,
                    attachments_metadata,
                    change_note,
                    clarifications,
                    source_analysis_job_id,
                    parent_version_id,
                    is_current,
                    created_by
                )
                values (
                    ?, ?, ?, ?, ?,
                    cast(? as jsonb),
                    cast(? as jsonb),
                    ?, cast(? as jsonb), ?, ?, true, ?
                )
                returning
                    id,
                    requirement_id,
                    version_number,
                    title,
                    original_content,
                    refined_content,
                    acceptance_criteria,
                    attachments_metadata,
                    change_note,
                    clarifications,
                    source_analysis_job_id,
                    parent_version_id,
                    is_current,
                    created_by,
                    created_at
                """,
                this::mapRow,
                requirementId,
                versionNumber,
                request.title(),
                request.originalContent(),
                request.refinedContent(),
                writeArray(request.acceptanceCriteria()),
                writeArray(request.attachmentsMetadata()),
                request.changeNote(),
                writeArray(clarifications),
                sourceAnalysisJobId,
                parentVersionId,
                userId
        );
    }

    public Optional<RequirementVersion> findCurrent(
            UUID requirementId
    ) {
        return jdbcTemplate.query(
                """
                select
                    id,
                    requirement_id,
                    version_number,
                    title,
                    original_content,
                    refined_content,
                    acceptance_criteria,
                    attachments_metadata,
                    change_note,
                    clarifications,
                    source_analysis_job_id,
                    parent_version_id,
                    is_current,
                    created_by,
                    created_at
                from public.requirement_versions
                where requirement_id = ?
                  and is_current = true
                """,
                this::mapRow,
                requirementId
        ).stream().findFirst();
    }

    public Optional<RequirementVersion> findById(UUID versionId) {
        return jdbcTemplate.query(
                """
                select
                    id,
                    requirement_id,
                    version_number,
                    title,
                    original_content,
                    refined_content,
                    acceptance_criteria,
                    attachments_metadata,
                    change_note,
                    clarifications,
                    source_analysis_job_id,
                    parent_version_id,
                    is_current,
                    created_by,
                    created_at
                from public.requirement_versions
                where id = ?
                """,
                this::mapRow,
                versionId
        ).stream().findFirst();
    }

    private RequirementVersion mapRow(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new RequirementVersion(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("requirement_id", UUID.class),
                resultSet.getInt("version_number"),
                resultSet.getString("title"),
                resultSet.getString("original_content"),
                resultSet.getString("refined_content"),
                readArray(resultSet.getString("acceptance_criteria")),
                readArray(resultSet.getString("attachments_metadata")),
                resultSet.getString("change_note"),
                readArray(resultSet.getString("clarifications")),
                resultSet.getObject("source_analysis_job_id", UUID.class),
                resultSet.getObject("parent_version_id", UUID.class),
                resultSet.getBoolean("is_current"),
                resultSet.getObject("created_by", UUID.class),
                resultSet.getObject(
                        "created_at",
                        java.time.OffsetDateTime.class
                )
        );
    }

    private String writeArray(List<Object> values) {
        try {
            return objectMapper.writeValueAsString(
                    values == null ? List.of() : values
            );
        } catch (JacksonException exception) {
            throw internalError(
                    "요구사항 버전 JSON을 직렬화하지 못했습니다.",
                    exception
            );
        }
    }

    private List<Object> readArray(String json) {
        try {
            Object value = objectMapper.readValue(json, Object.class);

            if (!(value instanceof List<?> items)) {
                throw internalError(
                        "저장된 요구사항 버전 JSON이 배열이 아닙니다."
                );
            }

            return new ArrayList<>(items);
        } catch (JacksonException exception) {
            throw internalError(
                    "저장된 요구사항 버전 JSON을 읽지 못했습니다.",
                    exception
            );
        }
    }

    private ResponseStatusException internalError(String message) {
        return new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                message
        );
    }

    private ResponseStatusException internalError(
            String message,
            Throwable cause
    ) {
        return new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                message,
                cause
        );
    }
}
