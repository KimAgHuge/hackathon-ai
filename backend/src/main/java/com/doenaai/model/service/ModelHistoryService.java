package com.doenaai.model.service;

import com.doenaai.model.repository.ModelHistoryRepository;
import com.doenaai.model.repository.ModelHistoryRepository.Deployment;
import com.doenaai.model.repository.ModelHistoryRepository.Version;
import com.doenaai.model.repository.ModelOperationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 원격 실행을 반복하지 않고 검증된 완료 결과를 모델 버전과 배포 이력에 연결한다. */
@Service
@RequiredArgsConstructor
public class ModelHistoryService {
    private final ModelHistoryRepository repository;
    private final ModelOperationRepository operations;
    private final ModelExecutionClient client;

    public void registration(Map<String, Object> origin, Map<String, Object> ready) {
        if (!"READY".equals(ready.get("status"))) return;
        UUID job = uuid(origin.get("id"));
        UUID actor = uuid(origin.get("actorId"));
        var target = map(map(origin.get("result")).get("target"));
        String model = model(ready.get("model"));
        String source = model(ready.get("sourceModel"));
        String base = model(ready.get("baseModel"));
        String revision = text(ready.get("baseRevision"), "[0-9a-f]{40}");
        String digest = digest(ready.get("digest"));
        String artifact = text(ready.get("artifactSha256"), "[0-9a-f]{64}");
        String manifest = text(ready.get("manifestSha256"), "[0-9a-f]{64}");
        long size = integer(ready.get("artifactSize"), 24, 64L * 1024 * 1024 * 1024);
        if (!"TRAINING_START".equals(origin.get("action")) || !"SUCCEEDED".equals(origin.get("status"))
                || !client.targetKey(true).equals(origin.get("targetKey"))
                || !job.toString().equals(ready.get("jobId")) || !("doenaai-trained:web-" + job).equals(model)
                || !("trained-web-" + job).equals(ready.get("version"))
                || !source.equals(target.get("sourceModel")) || !base.equals(target.get("baseModel"))
                || !revision.equals(target.get("revision")) || !"Q4_K_M".equals(ready.get("quantization"))) throw invalid();
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("historySource", "TRAINED_MODEL_IMPORT");
        metadata.put("registrationVerified", true);
        metadata.put("trainingJobId", job.toString()); metadata.put("sourceModel", source);
        metadata.put("baseRevision", revision); metadata.put("manifestSha256", manifest);
        metadata.put("artifactSha256", artifact); metadata.put("artifactSize", size);
        metadata.put("quantization", "Q4_K_M"); metadata.put("engineDigest", digest);
        metadata.put("registeredObservedAt", Instant.now().toString()); metadata.put("qualityEvaluated", false);
        repository.registration(new Version(model, "trained-web-" + job, "MERGED", base, artifact, actor, metadata));
    }

    public void deployment(Map<String, Object> operation) {
        deployment(operation, true);
    }

    /** 목록 조회에서는 누락된 모델 버전을 원격 조회하지 않고 동기화가 필요함을 알린다. */
    public void deploymentFromList(Map<String, Object> operation) {
        deployment(operation, false);
    }

    private void deployment(Map<String, Object> operation, boolean recoverRegistration) {
        String action = String.valueOf(operation.get("action"));
        String status = String.valueOf(operation.get("status"));
        if (!("MODEL_ACTIVATE".equals(action) && "APPLIED".equals(status))
                && !("MODEL_ROLLBACK".equals(action) && "ROLLED_BACK".equals(status))) return;
        if (!client.targetKey(false).equals(operation.get("targetKey"))) throw invalid();
        UUID id = uuid(operation.get("id")); UUID actor = uuid(operation.get("actorId"));
        var result = map(operation.get("result"));
        if (result.containsKey("status") && !status.equals(result.get("status"))) throw invalid();
        String model = model(result.get("model")); String digest = digest(result.get("digest"));
        long revision = integer(result.get("revision"), 1, 999_999_999_999_999_999L);
        String server = text(client.inferenceServerKey(), "[0-9a-f]{64}");
        Instant recordedAt = instant(operation.get("updatedAt"));
        String timeSource = recordedAt == null ? "OBSERVED" : "OPERATION_CONFIRMATION";
        if (recordedAt == null) recordedAt = Instant.now();
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("historySource", "MODEL_EXECUTION"); metadata.put("operationId", id.toString());
        metadata.put("action", action); metadata.put("model", model); metadata.put("engineDigest", digest);
        metadata.put("selectionRevision", revision); metadata.put("recordedObservedAt", Instant.now().toString());
        metadata.put("deploymentTimeSource", timeSource);
        metadata.put("operationRecordedAt", recordedAt.toString());
        if (result.get("previousModel") instanceof String previous) metadata.put("previousModel", model(previous));
        var deployment = new Deployment(id, server, revision, actor, recordedAt, metadata);
        if (model.startsWith("doenaai-trained:web-")) {
            UUID job = uuid(model.substring("doenaai-trained:web-".length()));
            if (!job.toString().equals(result.get("trainingJobId")) || !Boolean.TRUE.equals(result.get("trainedModel"))) throw invalid();
            var version = repository.trainedVersion(model, digest);
            if (version.isEmpty()) {
                if (!recoverRegistration) throw invalid();
                var ready = client.inference("GET", "/api/models/trained/imports/" + job, Map.of(), false);
                if (!"READY".equals(ready.get("status")) || !digest.equals(digest(ready.get("digest")))) throw invalid();
                registration(operations.find(job).orElseThrow(ModelHistoryService::invalid), ready);
                version = repository.trainedVersion(model, digest);
            }
            repository.deployment(version.orElseThrow(ModelHistoryService::invalid), null, deployment);
        } else {
            if (Boolean.TRUE.equals(result.get("trainedModel")) || "mlx".equals(result.get("provider")) || result.containsKey("adapterId")) throw invalid();
            var versionMetadata = Map.<String, Object>of("historySource", "RUNTIME_SELECTION", "engineDigest", digest,
                    "registeredObservedAt", Instant.now().toString(), "qualityEvaluated", false);
            repository.deployment(null, new Version(model, "installed-" + digest, "BASE", model, digest, actor, versionMetadata), deployment);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return value instanceof Map<?, ?> data ? (Map<String, Object>) data : Map.of(); }
    private static UUID uuid(Object value) {
        try { UUID id = UUID.fromString(String.valueOf(value)); if (id.toString().equals(value)) return id; }
        catch (IllegalArgumentException ignored) { }
        throw invalid();
    }
    private static String text(Object value, String pattern) {
        if (value instanceof String text && text.matches(pattern)) return text;
        throw invalid();
    }
    private static String model(Object value) {
        String text = text(value, "[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,199}");
        if (text.contains("://") || text.contains("..")) throw invalid();
        return text;
    }
    private static String digest(Object value) { return text(value, "(?:sha256:)?[0-9a-f]{64}").replaceFirst("^sha256:", ""); }
    private static long integer(Object value, long minimum, long maximum) {
        if (value instanceof Number number) try {
            long parsed = new BigDecimal(number.toString()).longValueExact();
            if (parsed >= minimum && parsed <= maximum) return parsed;
        } catch (NumberFormatException | ArithmeticException ignored) { }
        throw invalid();
    }
    private static Instant instant(Object value) {
        if (value == null) return null;
        try { return Instant.parse(String.valueOf(value)); }
        catch (java.time.format.DateTimeParseException ignored) { throw invalid(); }
    }
    private static IllegalStateException invalid() { return new IllegalStateException("완료 결과와 원본 모델 정보가 일치하지 않아 이력을 자동 기록하지 못했습니다."); }
}
