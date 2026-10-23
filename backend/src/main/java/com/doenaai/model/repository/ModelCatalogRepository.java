package com.doenaai.model.repository;

import com.doenaai.model.dto.ModelEvaluationRequest;
import com.doenaai.model.dto.ModelVersionCreateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 기존 모델 관리 테이블에서 화면에 필요한 필드만 읽고 등록 기록을 저장한다. */
@Repository
@RequiredArgsConstructor
public class ModelCatalogRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private static final String VERSION_SELECT = """
            select v.id, v.model_id, m.name as model_name, m.description, v.version,
                   v.artifact_type, v.base_model_ref, v.status, v.metadata, v.created_at,
                   v.artifact_uri, v.adapter_uri, v.checksum,
                   e.status as latest_evaluation_status, e.overall_score as latest_evaluation_score
            from public.model_versions v join public.ai_models m on m.id = v.model_id
            left join lateral (
                select status, overall_score from public.model_evaluations
                where model_version_id = v.id order by created_at desc, id desc limit 1
            ) e on true
            """;
    private static final String DEPLOYMENT_SELECT = """
            select d.id, d.model_version_id, m.name as model_name, v.version,
                   d.server_name, d.deployment_role, d.status, d.deployed_at
            from public.model_deployments d
            join public.model_versions v on v.id = d.model_version_id
            join public.ai_models m on m.id = v.model_id
            """;

    public List<Map<String, Object>> models(int limit) {
        return jdbc.query("""
                select id, name, description, provider, base_model_ref, status
                from public.ai_models order by created_at desc, id desc limit ?
                """, (row, index) -> fields(row, "id", "name", "description", "provider", "baseModelRef", "status"), limit);
    }

    public List<Map<String, Object>> versions(int limit) {
        return jdbc.query(VERSION_SELECT + " order by v.created_at desc, v.id desc limit ?", versionMapper(false), limit);
    }

    public List<Map<String, Object>> trainingJobs(int limit) {
        return jdbc.query("""
                select j.id, j.model_id, m.name as model_name, j.status, j.progress,
                       j.worker_name, j.created_at, j.started_at, j.completed_at
                from public.training_jobs j join public.ai_models m on m.id = j.model_id
                order by j.created_at desc, j.id desc limit ?
                """, (row, index) -> fields(row, "id", "modelId", "modelName", "status", "progress",
                    "workerName", "createdAt", "startedAt", "completedAt"), limit);
    }

    public List<Map<String, Object>> deployments(int limit) {
        return jdbc.query(DEPLOYMENT_SELECT + " order by d.created_at desc, d.id desc limit ?", deploymentMapper(), limit);
    }

    public Optional<Map<String, Object>> version(UUID id) {
        return jdbc.query(VERSION_SELECT + " where v.id = ?", versionMapper(true), id).stream().findFirst();
    }

    public List<Map<String, Object>> versionDeployments(UUID id) {
        return jdbc.query(DEPLOYMENT_SELECT + " where d.model_version_id = ? order by d.created_at desc, d.id desc limit ?",
                deploymentMapper(), id, 101);
    }

    public List<Map<String, Object>> evaluations(UUID id) {
        return jdbc.query("""
                select id, evaluation_type, status, metrics, overall_score, notes, evaluated_at
                from public.model_evaluations where model_version_id = ? order by created_at desc, id desc limit ?
                """, (row, index) -> {
                    var value = fields(row, "id", "evaluationType", "status", "overallScore", "notes", "evaluatedAt");
                    value.put("metrics", json(row.getString("metrics")));
                    return value;
                }, id, 101);
    }

    public boolean modelExists(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from public.ai_models where id = ?)", Boolean.class, id));
    }

    public void createModel(UUID id, ModelVersionCreateRequest request, UUID userId) {
        jdbc.update("""
                insert into public.ai_models (id, name, description, provider, base_model_ref, created_by)
                values (?, ?, ?, ?, ?, ?)
                """, id, request.modelName(), request.modelDescription(), request.provider(), request.baseModelRef(), userId);
    }

    public void createVersion(UUID id, UUID modelId, ModelVersionCreateRequest request, UUID userId) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("datasetName", request.datasetName());
        metadata.put("notes", request.notes());
        jdbc.update("""
                insert into public.model_versions
                    (id, model_id, version, artifact_type, status, base_model_ref, storage_provider,
                     artifact_uri, adapter_uri, checksum, metadata, created_by)
                values (?, ?, ?, ?, 'READY', ?, 'LOCAL', ?, ?, ?, ?::jsonb, ?)
                """, id, modelId, request.version(), request.artifactType(), request.baseModelRef(),
                request.artifactUri(), request.adapterUri(), request.checksum(), mapper.writeValueAsString(metadata), userId);
    }

    public void createEvaluation(UUID id, UUID versionId, ModelEvaluationRequest request, UUID userId) {
        jdbc.update("""
                insert into public.model_evaluations
                    (id, model_version_id, evaluation_type, status, metrics, overall_score, notes, evaluated_by, evaluated_at)
                values (?, ?, 'MANUAL', ?, ?::jsonb, ?, ?, ?, now())
                """, id, versionId, request.status(), mapper.writeValueAsString(request.metrics()),
                request.overallScore(), request.notes(), userId);
    }

    private RowMapper<Map<String, Object>> versionMapper(boolean detail) {
        return (row, index) -> {
            var value = fields(row, "id", "modelId", "modelName", "version", "artifactType", "baseModelRef", "status",
                    "createdAt", "latestEvaluationStatus", "latestEvaluationScore");
            var metadata = json(row.getString("metadata"));
            value.put("datasetName", metadata.get("datasetName") instanceof String text ? text : null);
            if (metadata.get("historySource") instanceof String source
                    && List.of("TRAINED_MODEL_IMPORT", "RUNTIME_SELECTION").contains(source)) {
                value.put("historySource", source);
                value.put("registrationVerified", Boolean.TRUE.equals(metadata.get("registrationVerified")));
                value.put("runtimeModel", value.get("modelName"));
                if (metadata.get("engineDigest") instanceof String digest && digest.matches("[0-9a-f]{64}")) value.put("runtimeDigest", digest);
                if (metadata.get("trainingJobId") instanceof String job && job.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) value.put("trainingJobId", job);
            }
            if (detail) {
                value.putAll(fields(row, "description", "artifactUri", "adapterUri", "checksum"));
                value.put("notes", metadata.get("notes") instanceof String text ? text : null);
            }
            return value;
        };
    }

    private RowMapper<Map<String, Object>> deploymentMapper() {
        return (row, index) -> fields(row, "id", "modelVersionId", "modelName", "version", "serverName", "deploymentRole", "status", "deployedAt");
    }

    private Map<String, Object> json(String value) {
        return value == null ? Map.of() : mapper.readValue(value, new TypeReference<>() {});
    }

    private static LinkedHashMap<String, Object> fields(ResultSet row, String... names) throws SQLException {
        var values = new LinkedHashMap<String, Object>();
        for (String name : names) {
            String column = name.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
            Object value = row.getObject(column);
            values.put(name, value instanceof java.sql.Timestamp time ? time.toInstant().toString() : value);
        }
        return values;
    }
}
