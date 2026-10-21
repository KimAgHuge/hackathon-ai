package com.doenaai.model.service;

import com.doenaai.model.dto.ModelExecutionRequest;
import com.doenaai.model.repository.ModelOperationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;

/** 원격 실행보다 DB 기록을 먼저 확정하며 통신 실패를 실제 실행 실패로 단정하지 않는다. */
@Service
@RequiredArgsConstructor
public class ModelExecutionService {
    private static final Set<String> ACTIVE = Set.of("PENDING", "PREPARING", "TRAINING", "VERIFYING", "UNKNOWN");
    private static final Set<String> STATES = Set.of("PENDING", "PREPARING", "TRAINING", "VERIFYING", "SUCCEEDED", "FAILED", "CANCELLED", "APPLIED", "ROLLED_BACK", "PASSED");
    private final ModelOperationRepository repository;
    private final ModelExecutionClient client;

    public Map<String, Object> installed() {
        try {
            var remote = client.inference("GET", "/api/models/installed", Map.of(), false);
            var result = snapshot(remote);
            result.put("canRollback", Boolean.TRUE.equals(remote.get("canRollback")));
            var models = new ArrayList<Map<String, Object>>();
            if (remote.get("models") instanceof List<?> rows) for (Object row : rows.stream().limit(200).toList()) {
                if (!(row instanceof Map<?, ?> item) || !(item.get("name") instanceof String name) || !validModel(name)) continue;
                var value = new LinkedHashMap<String, Object>();
                value.put("name", name);
                value.put("digest", safeDigest(item.get("digest")));
                value.put("size", item.get("size") instanceof Number number ? Math.max(0, number.longValue()) : null);
                value.put("selectable", Boolean.TRUE.equals(item.get("selectable")));
                value.put("reason", Boolean.TRUE.equals(item.get("selectable")) ? "시험 응답 확인 후 선택할 수 있습니다." : "이 분석 경로에서 사용할 수 없는 모델입니다.");
                adapterMetadata(item, value);
                value.put("capabilities", item.get("capabilities") instanceof List<?> list ? list.stream().filter(v -> v instanceof String text && text.matches("[a-z_]{1,40}")).limit(20).toList() : List.of());
                models.add(value);
            }
            result.put("models", models);
            return result;
        } catch (ModelExecutionClient.RemoteFailure failure) { throw unavailable(); }
    }

    public Map<String, Object> execute(String action, ModelExecutionRequest supplied, UUID actor, UUID jobId) {
        var input = supplied == null ? new ModelExecutionRequest(null, null, null, null, null, null) : supplied;
        UUID id = input.operationId() == null ? UUID.randomUUID() : input.operationId();
        boolean training = action.startsWith("TRAINING_");
        String target = client.targetKey(training);
        var safe = request(action, input, jobId);
        // 별도 트랜잭션에서 기록이 저장되지 않으면 외부 실행은 시작하지 않는다.
        boolean created = repository.create(id, action, actor, training ? "AI_TRAINING" : "AI_INFERENCE", target, safe);
        if (!created) {
            var existing = find(id);
            if (!action.equals(existing.get("action")) || !actor.toString().equals(existing.get("actorId"))
                    || !target.equals(existing.get("targetKey")) || !sameRequest(safe, existing.get("request"))) throw conflict();
            var checked = reconcile(existing);
            if (!ACTIVE.contains(checked.get("status")) || action.equals("MODEL_PROBE")) return publicValue(checked);
            // 사용자가 같은 식별자로 명시적으로 다시 요청한 경우에만 멱등 API에 재전송한다.
            // 목록·상태 조회에서는 재전송하지 않으며 원격 서버가 같은 식별자의 중복 실행을 막는다.
        }
        var body = new LinkedHashMap<>(safe);
        body.remove("jobId");
        body.remove("probeTokenHash");
        String path;
        switch (action) {
            case "MODEL_PROBE" -> path = "/api/models/probe";
            case "MODEL_ACTIVATE" -> {
                path = "/api/models/activate";
                body.put("operationId", id.toString());
                body.put("probeToken", input.probeToken());
            }
            case "MODEL_ROLLBACK" -> { path = "/api/models/rollback"; body.put("operationId", id.toString()); }
            case "TRAINING_START" -> { path = "/api/training/jobs"; body.put("jobId", id.toString()); }
            case "TRAINING_CANCEL" -> path = "/api/training/jobs/" + jobId + "/cancel";
            default -> throw invalid();
        }
        try {
            // 분석에 사용하는 모델과 별개로 화면에서 선택한 설치 모델을 학습한다.
            if (action.equals("TRAINING_START") && !installedSelection(input.model()))
                return publicValue(repository.record(id, created ? "FAILED" : "UNKNOWN", "TRAINING_MODEL_NOT_INSTALLED", Map.of()));
            var remote = training ? client.training("POST", path, body) : client.inference("POST", path, body, action.equals("MODEL_PROBE"));
            var state = snapshot(remote);
            String status = state.get("status") instanceof String value ? value : "UNKNOWN";
            if (action.equals("TRAINING_CANCEL")) {
                var origin = find(jobId);
                repository.record(jobId, status, code(remote.get("errorCode")), state);
                // 취소 요청의 결과는 대상 학습 작업의 진행 상태와 나누어 보관한다.
                state.put("jobId", origin.get("id"));
                status = Set.of("CANCELLED", "SUCCEEDED", "FAILED").contains(status) ? "SUCCEEDED" : "UNKNOWN";
            }
            var record = repository.record(id, status, code(remote.getOrDefault("errorCode", remote.get("code"))), state);
            var result = publicValue(record);
            // 시험 토큰은 브라우저에만 반환하고 DB의 요청·결과·로그에는 보관하지 않는다.
            if (action.equals("MODEL_PROBE") && "PASSED".equals(status)) {
                if (remote.get("probeToken") instanceof String token && token.matches("[A-Za-z0-9_\\-]{16,256}")) result.put("probeToken", token);
                if (remote.get("expiresAt") instanceof String expires && expires.matches("[0-9T:.+Z\\-]{10,40}")) result.put("expiresAt", expires);
                if (remote.get("currentRevision") instanceof Number revision) result.put("currentRevision", revision.longValue());
            }
            return result;
        } catch (ModelExecutionClient.RemoteFailure failure) {
            boolean uncertain = failure.uncertain || "MODEL_TRANSITION_BUSY".equals(failure.code);
            return publicValue(repository.record(id, uncertain ? "UNKNOWN" : "FAILED", failure.code, Map.of()));
        }
    }

    public Map<String, Object> training() { return training(null); }

    public Map<String, Object> training(String selectedModel) {
        if (selectedModel != null && !validModel(selectedModel)) throw invalid();
        String target = client.targetKey(true);
        boolean executable = false;
        String reasonCode = null;
        String reason = "AI 학습 서버 주소·전송 키·실행 설정과 서버 업데이트를 확인해 주세요.";
        Map<String, Object> dataset = Map.of();
        Map<String, Object> trainingProfile = Map.of();
        Map<String, Object> modelExport = Map.of();
        String requestedModel = selectedModel == null ? "" : selectedModel;
        Map<String, Map<String, Object>> remoteJobs = new HashMap<>();
        try {
            requestedModel = selectedModel == null ? currentInferenceModel() : selectedTrainingModel(selectedModel);
            var capabilities = client.training("GET", "/api/training/capabilities?model="
                    + java.net.URLEncoder.encode(requestedModel, java.nio.charset.StandardCharsets.UTF_8), Map.of());
            reasonCode = code(capabilities.get("trainingReasonCode"));
            dataset = safeDataset(capabilities.get("dataset"));
            trainingProfile = safeTrainingProfile(capabilities.get("trainingProfile"));
            modelExport = safeModelExport(capabilities.get("modelExport"));
            if (dataset.get("target") instanceof Map<?, ?> selected && !requestedModel.equals(trainingSelection(selected))) dataset = Map.of();
            executable = Boolean.TRUE.equals(capabilities.get("trainingExecution")) && dataset.containsKey("target");
            reason = executable ? "검증된 수신 자료로 짧은 시험 학습을 시작할 수 있습니다. 운영 모델은 자동 변경되지 않습니다."
                    : "MODEL_NOT_REGISTERED".equals(capabilities.get("trainingReasonCode"))
                    ? "선택한 모델에 대응하는 로컬 MLX 모델이 학습 서버에 등록되지 않았습니다. 다른 모델로 대신 학습하지 않습니다."
                    : "TRAINER_UNSUPPORTED".equals(capabilities.get("trainingReasonCode"))
                    ? "대응하는 MLX 모델은 등록됐지만 이 모델의 학습기는 아직 지원되지 않습니다."
                    : "AI 학습 서버의 모델 경로·호환성·실행 설정을 확인해 주세요.";
            var listing = client.training("GET", "/api/training/jobs", Map.of());
            if (listing.get("jobs") instanceof List<?> rows) for (Object row : rows) {
                if (row instanceof Map<?, ?> entry && entry.get("id") instanceof String key) {
                    var item = new LinkedHashMap<String, Object>();
                    entry.forEach((k, v) -> { if (k instanceof String text) item.put(text, v); });
                    remoteJobs.put(key, item);
                }
            }
        } catch (ModelExecutionClient.RemoteFailure failure) {
            reasonCode = failure.code;
            executable = false;
            reason = "AI 학습 서버의 설정 또는 작업 상태를 확인하지 못했습니다. 저장 이력은 보존하며 새 실행은 잠시 막습니다.";
        }
        var rows = repository.training(target, requestedModel, 101);
        var combined = new LinkedHashMap<String, Map<String, Object>>();
        for (var row : rows.stream().limit(100).toList()) combined.put((String) row.get("id"), row);
        // 오래된 진행 작업도 선택 모델이나 최근 완료 목록의 개수 때문에 사라지지 않게 한다.
        for (var row : repository.activeTraining(target)) combined.putIfAbsent((String) row.get("id"), row);
        var jobs = new ArrayList<Map<String, Object>>();
        var activeJobs = new LinkedHashMap<String, Map<String, Object>>();
        for (var row : combined.values()) {
            var remote = remoteJobs.get(row.get("id"));
            if (remote != null && ACTIVE.contains(row.get("status"))) row = recordRemote(row, remote);
            var job = publicValue(row);
            boolean active = ACTIVE.contains(job.get("status"));
            if (active) activeJobs.put((String) row.get("id"), job);
            if (active || requestedModel.equals(trainingSelection(row))) jobs.add(job);
        }
        for (var entry : remoteJobs.entrySet()) if (entry.getValue().get("status") instanceof String state && ACTIVE.contains(state)) {
            var job = snapshot(entry.getValue());
            if (validUuid(entry.getKey())) {
                job.put("id", entry.getKey());
                activeJobs.putIfAbsent(entry.getKey(), job);
            }
        }
        var result = new LinkedHashMap<String, Object>();
        var capabilities = new LinkedHashMap<String, Object>();
        capabilities.put("trainingExecution", executable); capabilities.put("reason", reason);
        if (reasonCode != null) capabilities.put("reasonCode", reasonCode);
        result.put("capabilities", capabilities);
        result.put("dataset", dataset); result.put("requestedModel", requestedModel); result.put("selectedModel", selectedModel == null ? requestedModel : selectedModel); result.put("jobs", jobs);
        if (!trainingProfile.isEmpty()) result.put("trainingProfile", trainingProfile);
        if (!modelExport.isEmpty()) result.put("modelExport", modelExport);
        result.put("busy", !activeJobs.isEmpty()); result.put("activeJobs", new ArrayList<>(activeJobs.values()));
        result.put("limit", 100); result.put("truncated", rows.size() > 100);
        return result;
    }

    private static String sourceModel(Map<?, ?> row) {
        if (row.get("result") instanceof Map<?, ?> result && result.get("target") instanceof Map<?, ?> target
                && target.get("sourceModel") instanceof String model && validModel(model)) return model;
        if (row.get("request") instanceof Map<?, ?> request && request.get("model") instanceof String model && validModel(model)) return model;
        return "";
    }

    /** 자료 출처와 구분해 사용자가 실제 선택한 연속 학습 대상을 보존한다. */
    private static String trainingSelection(Map<?, ?> row) {
        if (row.get("result") instanceof Map<?, ?> result && result.get("target") instanceof Map<?, ?> target
                && target.get("selectedModel") instanceof String model && validModel(model)) return model;
        if (row.get("request") instanceof Map<?, ?> request && request.get("model") instanceof String model && validModel(model)) return model;
        if (row.get("selectedModel") instanceof String model && validModel(model)) return model;
        if (!sourceModel(row).isBlank()) return sourceModel(row);
        return row.get("sourceModel") instanceof String model && validModel(model) ? model : "";
    }

    /** 모델명은 설치 목록에 있는 식별자와 정확히 일치해야 한다. */
    private boolean installedSelection(String model) {
        var status = client.inference("GET", "/api/models/installed", Map.of(), false);
        if (status == null || !(status.get("models") instanceof List<?> rows))
            throw new ModelExecutionClient.RemoteFailure("MODEL_SELECTION_UNAVAILABLE", false, 503);
        return rows.stream().anyMatch(row -> row instanceof Map<?, ?> item && model.equals(item.get("name")));
    }

    private String selectedTrainingModel(String selectedModel) {
        var installed = client.inference("GET", "/api/models/installed", Map.of(), false);
        if (installed == null || !(installed.get("models") instanceof List<?> rows))
            throw new ModelExecutionClient.RemoteFailure("MODEL_SELECTION_UNAVAILABLE", false, 503);
        for (var row : rows) if (row instanceof Map<?, ?> item && selectedModel.equals(item.get("name"))) return selectedModel;
        throw new ModelCatalogException(HttpStatus.BAD_REQUEST, "선택한 학습 모델이 분석 서버의 설치 목록에 없습니다. 모델 목록을 새로고침해 주세요.");
    }

    /** 별도 선택이 없을 때는 추론 서버의 현재 모델을 학습 조회 기준으로 사용한다. */
    private String currentInferenceModel() {
        var status = client.inference("GET", "/api/models/installed", Map.of(), false);
        if (status == null || !(status.get("model") instanceof String model) || !validModel(model))
            throw new ModelExecutionClient.RemoteFailure("MODEL_SELECTION_UNAVAILABLE", false, 503);
        return model;
    }

    /** 모델 식별자와 비식별 사례 식별자만 화면에 전달한다. 원문과 로컬 경로는 전달하지 않는다. */
    private static Map<String, Object> safeDataset(Object supplied) {
        var result = new LinkedHashMap<String, Object>();
        if (!(supplied instanceof Map<?, ?> data)) return result;
        var target = safeTrainingTarget(data.get("target"));
        if (target.isEmpty()) return result;
        result.put("target", target);
        var samples = new ArrayList<Map<String, Object>>();
        if (data.get("samples") instanceof List<?> rows) for (var row : rows.stream().limit(100).toList()) {
            if (row instanceof Map<?, ?> item && item.get("caseId") instanceof String id
                    && id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}") && target.get("sourceModel").equals(item.get("sourceModel")))
                samples.add(Map.of("caseId", id, "sourceModel", target.get("sourceModel")));
        }
        result.put("samples", samples);
        for (String key : List.of("total", "excluded")) if (data.get(key) instanceof Number count && count.longValue() >= 0) result.put(key, count.longValue());
        result.put("truncated", Boolean.TRUE.equals(data.get("truncated")));
        return result;
    }

    private static Map<String, Object> safeTrainingTarget(Object supplied) {
        if (supplied instanceof Map<?, ?> target && target.get("sourceModel") instanceof String source && validModel(source)
                && target.get("baseModel") instanceof String base && validModel(base)
                && target.get("revision") instanceof String revision && revision.matches("[0-9a-f]{40}") && safeDigest(target.get("digest")) != null) {
            var result = new LinkedHashMap<String, Object>();
            result.put("sourceModel", source); result.put("baseModel", base); result.put("revision", revision); result.put("digest", safeDigest(target.get("digest")));
            if (target.get("selectedModel") instanceof String selected && validModel(selected)) result.put("selectedModel", selected);
            if (target.get("resumeJobId") instanceof String id && validUuid(id)) result.put("resumeJobId", id);
            if (safeDigest(target.get("parentAdapterSha256")) != null) result.put("parentAdapterSha256", safeDigest(target.get("parentAdapterSha256")));
            if (safeDigest(target.get("parentConfigSha256")) != null) result.put("parentConfigSha256", safeDigest(target.get("parentConfigSha256")));
            return result;
        }
        return Map.of();
    }

    /** 학습 프로필은 실행 설정의 숫자와 식별자만 전달하고 경로·명령은 제거한다. */
    private static Map<String, Object> safeTrainingProfile(Object supplied) {
        var result = new LinkedHashMap<String, Object>();
        if (!(supplied instanceof Map<?, ?> profile)) return result;
        if (profile.get("trainer") instanceof String trainer && trainer.matches("[a-z0-9-]{1,60}")) result.put("trainer", trainer);
        if (profile.get("modelType") instanceof String type && type.matches("[A-Za-z0-9_\\-]{1,100}")) result.put("modelType", type);
        for (String key : List.of("loraLayers", "loraRank", "maxSequenceLength", "batchSize")) {
            int minimum = key.equals("maxSequenceLength") ? 32 : 1;
            int maximum = key.equals("maxSequenceLength") ? 32768 : key.equals("batchSize") ? 4 : 128;
            if (profile.get(key) instanceof Number number && Double.isFinite(number.doubleValue())
                    && number.doubleValue() == number.longValue() && number.longValue() >= minimum && number.longValue() <= maximum)
                result.put(key, number.longValue());
        }
        if (profile.get("continuation") instanceof Boolean continuation) result.put("continuation", continuation);
        if (profile.get("parentJobId") instanceof String id && validUuid(id)) result.put("parentJobId", id);
        return result;
    }

    /** 변환 지원은 학습 실행 가능 여부와 별도로 표시하며 원격 설명 원문은 전달하지 않는다. */
    private static Map<String, Object> safeModelExport(Object supplied) {
        if (!(supplied instanceof Map<?, ?> export) || !(export.get("supported") instanceof Boolean supported)) return Map.of();
        var result = new LinkedHashMap<String, Object>();
        result.put("supported", supported);
        if (code(export.get("reasonCode")) != null) result.put("reasonCode", code(export.get("reasonCode")));
        if (export.get("architecture") instanceof String architecture && architecture.matches("[A-Za-z0-9_\\-]{1,100}")) result.put("architecture", architecture);
        result.put("message", supported
                ? "새 분석 모델을 만드는 변환 경로를 지원합니다. 실제 생성 전에 도구와 저장 공간을 확인합니다."
                : "이 모델의 새 분석 모델 변환은 아직 지원하지 않습니다. 어댑터 학습·저장·재로딩은 별도로 확인합니다.");
        return result;
    }

    public Map<String, Object> operations() {
        var rows = repository.recent(101);
        // 전환 응답이 끊긴 요청은 동일 식별자로 원격 저장 결과를 조회한다. 재실행하지 않는다.
        int checked = 0;
        var output = new ArrayList<Map<String, Object>>();
        for (var row : rows.stream().limit(100).toList()) {
            if (checked < 3 && Set.of("MODEL_ACTIVATE", "MODEL_ROLLBACK").contains(row.get("action")) && ACTIVE.contains(row.get("status"))) {
                row = reconcile(row); checked++;
            }
            output.add(publicValue(row));
        }
        return Map.of("operations", output, "limit", 100, "truncated", rows.size() > 100);
    }

    public Map<String, Object> operation(UUID id) {
        var value = publicValue(reconcile(find(id)));
        value.put("events", repository.events(id));
        return value;
    }

    private Map<String, Object> reconcile(Map<String, Object> row) {
        String action = (String) row.get("action");
        boolean training = action.startsWith("TRAINING_");
        if (!ACTIVE.contains(row.get("status")) || !client.targetKey(training).equals(row.get("targetKey"))) return row;
        try {
            if (action.equals("TRAINING_START")) return recordRemote(row, client.training("GET", "/api/training/jobs/" + row.get("id"), Map.of()));
            if (action.equals("TRAINING_CANCEL") && row.get("request") instanceof Map<?, ?> request
                    && request.get("jobId") instanceof String jobId) {
                var remote = client.training("GET", "/api/training/jobs/" + UUID.fromString(jobId), Map.of());
                var state = snapshot(remote);
                String status = (String) state.getOrDefault("status", "UNKNOWN");
                recordRemote(find(UUID.fromString(jobId)), remote);
                state.put("jobId", jobId);
                return repository.record(UUID.fromString((String) row.get("id")),
                        Set.of("CANCELLED", "SUCCEEDED", "FAILED").contains(status) ? "SUCCEEDED" : "UNKNOWN", null, state);
            }
            if (Set.of("MODEL_ACTIVATE", "MODEL_ROLLBACK").contains(action)) return recordRemote(row,
                    client.inference("GET", "/api/models/operations/" + row.get("id"), Map.of(), false));
            return row;
        } catch (ModelExecutionClient.RemoteFailure ignored) { return row; }
    }

    private Map<String, Object> recordRemote(Map<String, Object> row, Map<String, Object> remote) {
        var state = snapshot(remote);
        return repository.record(UUID.fromString((String) row.get("id")), (String) state.getOrDefault("status", "UNKNOWN"),
                code(remote.getOrDefault("errorCode", remote.get("code"))), state);
    }

    private Map<String, Object> request(String action, ModelExecutionRequest input, UUID jobId) {
        var value = new LinkedHashMap<String, Object>();
        if (Set.of("MODEL_PROBE", "MODEL_ACTIVATE").contains(action)) {
            if (!validModel(input.model()) || safeDigest(input.digest()) == null) throw invalid();
            value.put("model", input.model()); value.put("digest", input.digest());
        }
        if (Set.of("MODEL_ACTIVATE", "MODEL_ROLLBACK").contains(action)) {
            if (input.expectedRevision() == null || input.expectedRevision() < 0) throw invalid();
            value.put("expectedRevision", input.expectedRevision());
        }
        if (action.equals("MODEL_ACTIVATE")) {
            if (input.probeToken() == null || !input.probeToken().matches("[A-Za-z0-9_\\-]{20,200}")) throw invalid();
            // 원문 없이도 동일 요청인지 비교할 수 있도록 짧게 쓰는 시험 증거의 해시만 남긴다.
            try { value.put("probeTokenHash", HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(input.probeToken().getBytes(java.nio.charset.StandardCharsets.UTF_8)))); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("시험 증거 해시를 만들지 못했습니다."); }
        }
        if (action.equals("TRAINING_START")) {
            if (input.iterations() == null || input.iterations() < 1 || input.iterations() > 20) throw invalid();
            if (!validModel(input.model()) || safeDigest(input.digest()) == null) throw invalid();
            value.put("model", input.model()); value.put("digest", input.digest());
            value.put("iterations", input.iterations());
        }
        if (action.equals("TRAINING_CANCEL")) {
            var original = find(jobId);
            if (!"TRAINING_START".equals(original.get("action")) || !client.targetKey(true).equals(original.get("targetKey"))) throw conflict();
            value.put("jobId", jobId.toString());
        }
        return value;
    }

    /** 정해진 안전 필드만 보존한다. 원격 원문, 경로, 토큰과 임의 메모는 보존하지 않는다. */
    private static Map<String, Object> snapshot(Map<String, Object> remote) {
        var value = new LinkedHashMap<String, Object>();
        adapterMetadata(remote, value);
        var target = safeTrainingTarget(remote.get("target"));
        if (!target.isEmpty()) value.put("target", target);
        var profile = safeTrainingProfile(remote.get("trainingProfile"));
        if (!profile.isEmpty()) value.put("trainingProfile", profile);
        var export = safeModelExport(remote.get("modelExport"));
        if (!export.isEmpty()) value.put("modelExport", export);
        if (remote.get("status") instanceof String state && STATES.contains(state)) value.put("status", state);
        for (String key : List.of("model", "previousModel")) if (remote.get(key) instanceof String name && validModel(name)) value.put(key, name);
        for (String key : List.of("digest", "currentDigest")) if (safeDigest(remote.get(key)) != null) value.put(key, safeDigest(remote.get(key)));
        for (String key : List.of("revision", "currentRevision", "progress", "iterations")) if (remote.get(key) instanceof Number number && number.doubleValue() >= 0 && Double.isFinite(number.doubleValue())) value.put(key, number.longValue());
        if (remote.get("metrics") instanceof Map<?, ?> supplied) {
            var metrics = new LinkedHashMap<String, Object>();
            for (String key : List.of("trainingSamples", "validationSamples", "testSamples", "excludedTestSamples", "finalTrainingLoss",
                    "validationLossBefore", "validationLossAfter", "adapterTensors", "peakGpuGiB", "reloadMaxLogitDifference")) {
                if (supplied.get(key) instanceof Number number && Double.isFinite(number.doubleValue()) && number.doubleValue() >= 0) metrics.put(key, number);
            }
            metrics.put("qualityEvaluated", false);
            metrics.put("independentTestExecuted", false);
            value.put("metrics", metrics);
        }
        return value;
    }

    private Map<String, Object> publicValue(Map<String, Object> row) {
        var value = new LinkedHashMap<>(row);
        value.remove("request"); value.remove("targetKey");
        if (value.remove("result") instanceof Map<?, ?> result) result.forEach((key, item) -> { if (key instanceof String text) value.put(text, item); });
        value.put("status", row.get("status"));
        if ("TRAINING_START".equals(row.get("action")) && !sourceModel(row).isBlank()) value.put("sourceModel", sourceModel(row));
        if ("TRAINING_START".equals(row.get("action")) && !trainingSelection(row).isBlank()) value.put("selectedModel", trainingSelection(row));
        if (row.get("request") instanceof Map<?, ?> request && request.get("iterations") instanceof Number iterations) value.put("iterations", iterations);
        value.put("message", message((String) row.get("status"), (String) row.get("errorCode")));
        return value;
    }

    private Map<String, Object> find(UUID id) {
        return repository.find(id).orElseThrow(() -> new ModelCatalogException(HttpStatus.NOT_FOUND, "실행 기록을 찾을 수 없습니다."));
    }

    private static String message(String status, String code) {
        if ("TRAINING_MODEL_NOT_INSTALLED".equals(code)) return "UNKNOWN".equals(status)
                ? "선택 모델이 설치 목록에서 사라져 재전송하지 않았습니다. 기존 작업의 상태를 실행 이력에서 확인해 주세요."
                : "선택한 모델이 분석 서버에 설치돼 있지 않아 학습을 시작하지 않았습니다. 모델 목록을 새로고침해 주세요.";
        if ("UNCONFIGURED".equals(code)) return "대상 서버 주소와 인증 설정을 확인해 주세요. 원격 실행을 시작하지 않았습니다.";
        return switch (status) {
            case "PASSED" -> "짧은 JSON 응답 시험을 통과했습니다. 실제 업무 정확도나 긴 입력 처리를 보장하지는 않습니다.";
            case "APPLIED" -> "사용 모델을 전환했습니다. 새 분석 요청부터 적용됩니다.";
            case "ROLLED_BACK" -> "직전 사용 모델로 복구했습니다. 새 분석 요청부터 적용됩니다.";
            case "SUCCEEDED" -> "작업을 완료했습니다. 시험 학습의 독립 생성 평가·업무 품질 평가는 수행하지 않았으며 운영 배포나 품질 승인이 아닙니다.";
            case "FAILED" -> "작업을 완료하지 못했습니다. 오류 코드와 대상 서버 로그를 확인해 주세요.";
            case "CANCELLED" -> "학습을 취소했습니다. 기존 자료와 운영 모델은 유지됩니다.";
            case "UNKNOWN" -> "원격 처리 결과를 확인하지 못했습니다. 중복 실행하지 말고 실행 기록을 새로고침해 확인해 주세요.";
            case "PREPARING" -> "검증된 수신 자료에서 시험 학습 자료를 준비하고 있습니다.";
            case "TRAINING" -> "AI 학습 서버에서 시험 학습을 진행하고 있습니다.";
            case "VERIFYING" -> "저장한 어댑터를 별도 프로세스에서 다시 불러와 검사하고 있습니다.";
            default -> "요청을 기록했습니다. 대상 서버의 처리 결과를 확인하고 있습니다.";
        };
    }

    private static void adapterMetadata(Map<?, ?> supplied, Map<String, Object> result) {
        if ("ollama".equals(supplied.get("provider")) && Boolean.TRUE.equals(supplied.get("trainedModel"))
                && supplied.get("trainingJobId") instanceof String id && validUuid(id)
                && supplied.get("sourceModel") instanceof String source && validModel(source)) {
            result.put("provider", "ollama"); result.put("trainingJobId", id); result.put("sourceModel", source);
            result.put("trainedModel", true); result.put("qualityEvaluated", false);
            return;
        }
        if (!"mlx".equals(supplied.get("provider")) || !(supplied.get("adapterId") instanceof String adapter) || !validUuid(adapter)
                || !(supplied.get("sourceModel") instanceof String source) || !validModel(source)) return;
        result.put("provider", "mlx"); result.put("adapterId", adapter); result.put("sourceModel", source);
        result.put("qualityEvaluated", false);
    }

    private static boolean validUuid(String value) {
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException ignored) { return false; }
    }

    private static boolean validModel(String value) { return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,199}") && !value.contains("://") && !value.contains(".."); }
    private static String safeDigest(Object value) { return value instanceof String text && text.matches("(?:sha256:)?[0-9a-f]{64}") ? text : null; }
    private static String code(Object value) { return value instanceof String text && text.matches("[A-Z][A-Z0-9_]{0,59}") ? text : null; }
    private static boolean sameRequest(Map<String, Object> expected, Object stored) {
        if (!(stored instanceof Map<?, ?> actual) || !expected.keySet().equals(actual.keySet())) return false;
        return expected.entrySet().stream().allMatch(entry -> {
            Object left = entry.getValue(), right = actual.get(entry.getKey());
            if (left instanceof Number && right instanceof Number)
                return new java.math.BigDecimal(left.toString()).compareTo(new java.math.BigDecimal(right.toString())) == 0;
            return Objects.equals(left, right);
        });
    }
    private static ModelCatalogException invalid() { return new ModelCatalogException(HttpStatus.BAD_REQUEST, "모델 식별자·파일 해시·시험 토큰·설정 버전 또는 학습 횟수를 확인해 주세요."); }
    private static ModelCatalogException conflict() { return new ModelCatalogException(HttpStatus.CONFLICT, "같은 실행 식별자의 요청이 다르거나 다른 서버에 연결된 작업입니다. 실행 기록을 새로고침해 주세요."); }
    private static ModelCatalogException unavailable() { return new ModelCatalogException(HttpStatus.SERVICE_UNAVAILABLE, "AI 분석/추론 서버의 관리 API와 인증 설정을 확인해 주세요."); }
}
