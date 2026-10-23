package com.doenaai.model.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 확인된 등록과 전환만 기존 모델 관리 테이블에 멱등 저장한다. */
@Repository
@RequiredArgsConstructor
public class ModelHistoryRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public record Version(String model, String version, String artifactType, String baseModel,
                          String checksum, UUID actorId, Map<String, Object> metadata) { }

    public record Deployment(UUID operationId, String serverKey, long revision, UUID actorId,
                             Instant recordedAt, Map<String, Object> metadata) { }

    @Transactional
    public UUID registration(Version version) {
        return ensureVersion(version);
    }

    public Optional<UUID> trainedVersion(String model, String digest) {
        return jdbc.query("""
                select v.id from public.model_versions v join public.ai_models m on m.id = v.model_id
                where m.name = ? and v.artifact_type = 'MERGED' and v.metadata->>'engineDigest' = ?
                  and v.metadata->>'historySource' = 'TRAINED_MODEL_IMPORT'
                  and v.metadata->>'registrationVerified' = 'true'
                order by v.created_at, v.id limit 1
                """, (row, index) -> row.getObject("id", UUID.class), model, digest).stream().findFirst();
    }

    /** 서버별 잠금으로 중복 전환과 늦게 도착한 과거 기록을 함께 처리한다. */
    @Transactional
    public void deployment(UUID versionId, Version baseVersion, Deployment deployment) {
        String server = "ai-inference-" + deployment.serverKey();
        long lockKey = stableId("deployment-lock\n" + server).getMostSignificantBits();
        jdbc.queryForObject("select pg_advisory_xact_lock(?)", (row, index) -> 0, lockKey);
        UUID id = stableId("deployment\n" + server + "\n" + deployment.operationId());
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from public.model_deployments where id = ?)", Boolean.class, id))) return;

        Long latest = jdbc.queryForObject("""
                select max((metadata->>'selectionRevision')::bigint) from public.model_deployments
                where server_name = ? and deployment_role = 'ANALYSIS'
                  and metadata->>'historySource' = 'MODEL_EXECUTION'
                  and metadata->>'selectionRevision' ~ '^[0-9]{1,18}$'
                """, Long.class, server);
        if (latest != null && latest == deployment.revision()) throw new IllegalStateException("같은 모델 설정 버전의 다른 배포 기록이 있습니다.");
        boolean active = latest == null || latest < deployment.revision();
        UUID resolvedVersion = baseVersion == null ? versionId : ensureVersion(baseVersion);
        if (resolvedVersion == null) throw new IllegalStateException("배포 기록에 연결할 모델 버전이 없습니다.");
        if (active) jdbc.update("""
                update public.model_deployments set status = 'INACTIVE', undeployed_at = ?, updated_at = now()
                where server_name = ? and deployment_role = 'ANALYSIS' and status = 'ACTIVE'
                """, timestamp(deployment.recordedAt()), server);
        jdbc.update("""
                insert into public.model_deployments
                    (id, model_version_id, server_name, deployment_role, status, deployed_by, deployed_at, metadata)
                values (?, ?, ?, 'ANALYSIS', ?, ?, ?, ?::jsonb)
                """, id, resolvedVersion, server, active ? "ACTIVE" : "INACTIVE", deployment.actorId(),
                timestamp(deployment.recordedAt()), mapper.writeValueAsString(deployment.metadata()));
    }

    private UUID ensureVersion(Version version) {
        UUID modelId = stableId("model\n" + version.model());
        jdbc.update("""
                insert into public.ai_models (id, name, description, provider, base_model_ref, created_by)
                values (?, ?, ?, 'ollama', ?, ?) on conflict (name) do nothing
                """, modelId, version.model(), "실제 모델 실행 환경에서 확인한 모델입니다.", version.baseModel(), version.actorId());
        modelId = jdbc.queryForObject("select id from public.ai_models where name = ?", UUID.class, version.model());
        UUID id = stableId("version\n" + version.model() + "\n" + version.version());
        var rows = jdbc.query("""
                insert into public.model_versions
                    (id, model_id, version, artifact_type, status, base_model_ref, storage_provider,
                     artifact_uri, checksum, metadata, created_by)
                values (?, ?, ?, ?, 'READY', ?, 'LOCAL', ?, ?, ?::jsonb, ?)
                on conflict (model_id, version) do update
                set metadata = model_versions.metadata || excluded.metadata || jsonb_build_object(
                    'registeredObservedAt', coalesce(model_versions.metadata->'registeredObservedAt', excluded.metadata->'registeredObservedAt'))
                where model_versions.artifact_type = excluded.artifact_type
                  and model_versions.checksum = excluded.checksum
                  and model_versions.base_model_ref is not distinct from excluded.base_model_ref
                  and (model_versions.metadata->>'engineDigest' is null
                       or model_versions.metadata->>'engineDigest' = excluded.metadata->>'engineDigest')
                  and not exists (
                    select 1 from jsonb_each(excluded.metadata) expected(key, value)
                    where expected.key in ('trainingJobId', 'sourceModel', 'baseRevision', 'manifestSha256',
                                          'artifactSha256', 'artifactSize', 'quantization', 'engineDigest')
                      and jsonb_exists(model_versions.metadata, expected.key)
                      and model_versions.metadata->expected.key <> expected.value)
                  and not model_versions.metadata @> (excluded.metadata - 'registeredObservedAt')
                returning id
                """, (row, index) -> row.getObject("id", UUID.class), id, modelId, version.version(), version.artifactType(),
                version.baseModel(), "ollama:" + version.model(), version.checksum(), mapper.writeValueAsString(version.metadata()), version.actorId());
        if (rows.isEmpty()) rows = jdbc.query("""
                select id from public.model_versions
                where model_id = ? and version = ? and artifact_type = ? and checksum = ?
                  and base_model_ref is not distinct from ?
                  and metadata->>'engineDigest' = ?
                  and not exists (
                    select 1 from jsonb_each(?::jsonb) expected(key, value)
                    where expected.key in ('trainingJobId', 'sourceModel', 'baseRevision', 'manifestSha256',
                                          'artifactSha256', 'artifactSize', 'quantization', 'engineDigest')
                      and jsonb_exists(model_versions.metadata, expected.key)
                      and model_versions.metadata->expected.key <> expected.value)
                """, (row, index) -> row.getObject("id", UUID.class), modelId, version.version(), version.artifactType(),
                version.checksum(), version.baseModel(), version.metadata().get("engineDigest"), mapper.writeValueAsString(version.metadata()));
        if (rows.size() != 1) throw new IllegalStateException("같은 모델 버전의 파일 정보가 달라 자동 기록하지 못했습니다.");
        return rows.getFirst();
    }

    public static UUID stableId(String key) {
        return UUID.nameUUIDFromBytes(("doenaai-model-history-v1\n" + key).getBytes(StandardCharsets.UTF_8));
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
}
