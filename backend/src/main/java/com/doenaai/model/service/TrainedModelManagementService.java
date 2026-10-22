package com.doenaai.model.service;

import com.doenaai.model.repository.ModelOperationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 서버 주소와 경로는 설정으로만 정하고 검증한 모델 식별값만 두 서버 사이에 전달한다. */
@Service
@RequiredArgsConstructor
public class TrainedModelManagementService {
    private static final Set<String> BUILD_STATES = Set.of("NOT_BUILT", "PENDING", "FUSING", "CONVERTING", "QUANTIZING", "PUBLISHING", "SUCCEEDED", "FAILED", "CANCELLED", "UNAVAILABLE");
    private static final Set<String> IMPORT_STATES = Set.of("NOT_STARTED", "IMPORTING", "READY", "WAITING", "UNAVAILABLE");
    private static final Map<String, String> IMPORT_PHASE_MESSAGES = Map.of(
            "DOWNLOADING", "공유 저장소에 모델 파일 다운로드를 요청했습니다. AI 분석/추론 서버에 파일이 준비되기를 기다립니다.",
            "VERIFYING", "AI 분석/추론 서버에서 모델 파일의 서명·크기·해시를 검사하고 있습니다.",
            "COPYING", "검사한 모델 파일을 AI 분석/추론 서버에 보관하고 보관한 파일을 다시 확인하고 있습니다.",
            "REGISTERING", "AI 분석/추론 서버의 모델 실행 환경에 새 모델을 등록하고 있습니다.");
    private static final Set<String> METADATA = Set.of("jobId", "version", "manifestSha256", "sourceModel", "baseModel", "baseRevision", "model", "artifactSha256", "artifactSize", "quantization");
    private final ModelOperationRepository repository;
    private final ModelExecutionClient client;

    public Map<String, Object> build(UUID id, String method, Map<String, Object> body) {
        empty(body);
        var origin = origin(id);
        try { return buildValue(client.training(method, buildPath(id), Map.of()), id, origin); }
        catch (ModelExecutionClient.RemoteFailure failure) { return failure(id, origin, failure); }
    }

    public Map<String, Object> cancel(UUID id, Map<String, Object> body) {
        empty(body);
        var origin = origin(id);
        try { return buildValue(client.training("POST", buildPath(id) + "/cancel", Map.of()), id, origin); }
        catch (ModelExecutionClient.RemoteFailure failure) { return failure(id, origin, failure); }
    }

    public Map<String, Object> register(UUID id, Map<String, Object> body) {
        empty(body);
        var origin = origin(id);
        try {
            var built = client.training("GET", buildPath(id), Map.of());
            if (!"SUCCEEDED".equals(built.get("status"))) return unavailable(id, origin, "MODEL_BUILD_NOT_COMPLETE");
            var metadata = metadata(built.get("result"), id, origin);
            if (metadata == null) return unavailable(id, origin, "MODEL_BUILD_METADATA_INVALID");
            Map<String, Object> imported;
            try { imported = importValue(client.inference("POST", "/api/models/trained/import", metadata, false), id, origin); }
            catch (ModelExecutionClient.RemoteFailure failure) { return importFailure(id, origin, failure); }
            if (imported.containsKey("manifestSha256") && !metadata.get("manifestSha256").equals(imported.get("manifestSha256")))
                return importUnavailable(id, origin, "MODEL_IMPORT_METADATA_INVALID");
            return imported;
        } catch (ModelExecutionClient.RemoteFailure failure) { return failure(id, origin, failure); }
    }

    public Map<String, Object> importStatus(UUID id) {
        var origin = origin(id);
        try { return importValue(client.inference("GET", "/api/models/trained/imports/" + id, Map.of(), false), id, origin); }
        catch (ModelExecutionClient.RemoteFailure failure) { return importFailure(id, origin, failure); }
    }

    private Map<String, Object> origin(UUID id) {
        var row = repository.find(id).orElseThrow(() -> new ModelCatalogException(HttpStatus.NOT_FOUND, "학습 실행 기록을 찾을 수 없습니다."));
        if (!"TRAINING_START".equals(row.get("action")) || !client.targetKey(true).equals(row.get("targetKey")) || source(row) == null
                || !(row.get("result") instanceof Map<?, ?> data) || !(data.get("target") instanceof Map<?, ?> target)
                || !(target.get("baseModel") instanceof String base) || !validModel(base)
                || !(target.get("revision") instanceof String revision) || !revision.matches("[0-9a-f]{40}")
                || !source(row).equals(target.get("sourceModel")))
            throw new ModelCatalogException(HttpStatus.CONFLICT, "현재 학습 서버에서 실행한 모델 정보가 있는 작업만 사용할 수 있습니다.");
        return row;
    }

    private static Map<String, Object> buildValue(Map<String, Object> supplied, UUID id, Map<String, Object> origin) {
        if (supplied == null || !id.toString().equals(supplied.get("jobId")) || !(supplied.get("status") instanceof String status) || !BUILD_STATES.contains(status))
            return unavailable(id, origin, "MODEL_BUILD_RESPONSE_INVALID");
        var result = base(id, origin, status);
        safeFields(supplied, result);
        if (Boolean.FALSE.equals(result.get("available"))) result.put("message", readinessMessage((String) result.get("reasonCode")));
        if ("SUCCEEDED".equals(status)) {
            var metadata = metadata(supplied.get("result"), id, origin);
            if (metadata == null) return unavailable(id, origin, "MODEL_BUILD_METADATA_INVALID");
            result.put("result", metadata);
        }
        return result;
    }

    private static Map<String, Object> importValue(Map<String, Object> supplied, UUID id, Map<String, Object> origin) {
        if (supplied == null || !(supplied.get("status") instanceof String status) || !IMPORT_STATES.contains(status)
                || !id.toString().equals(supplied.get("jobId")) || !runtimeModel(id).equals(supplied.get("model")))
            return importUnavailable(id, origin, "MODEL_IMPORT_RESPONSE_INVALID");
        if (supplied.containsKey("sourceModel") && !source(origin).equals(supplied.get("sourceModel"))) return importUnavailable(id, origin, "MODEL_IMPORT_METADATA_INVALID");
        if ("READY".equals(status) && (!hash(supplied.get("digest")) || !hash(supplied.get("manifestSha256")))) return importUnavailable(id, origin, "MODEL_IMPORT_METADATA_INVALID");
        var result = base(id, origin, status);
        safeFields(supplied, result);
        if ("IMPORTING".equals(status) && supplied.get("phase") instanceof String phase && IMPORT_PHASE_MESSAGES.containsKey(phase)) {
            result.put("phase", phase);
            result.put("message", IMPORT_PHASE_MESSAGES.get(phase));
        }
        if ("UNAVAILABLE".equals(status)) result.put("message", importFailureMessage((String) result.get("errorCode")));
        for (String key : Set.of("digest", "manifestSha256")) if (hash(supplied.get(key))) result.put(key, supplied.get(key));
        return result;
    }

    private static Map<String, Object> metadata(Object value, UUID id, Map<String, Object> origin) {
        if (!(value instanceof Map<?, ?> data) || !id.toString().equals(data.get("jobId"))
                || !("trained-web-" + id).equals(data.get("version")) || !runtimeModel(id).equals(data.get("model"))
                || !source(origin).equals(data.get("sourceModel")) || !hash(data.get("manifestSha256")) || !hash(data.get("artifactSha256"))
                || !"Q4_K_M".equals(data.get("quantization")) || !(data.get("baseModel") instanceof String model) || !validModel(model)
                || !(data.get("baseRevision") instanceof String revision) || !revision.matches("[0-9a-f]{40}")
                || !(data.get("artifactSize") instanceof Number size) || size.longValue() <= 0 || size.longValue() > 64L * 1024 * 1024 * 1024
                || size.doubleValue() != size.longValue()) return null;
        if (origin.get("result") instanceof Map<?, ?> saved && saved.get("target") instanceof Map<?, ?> target
                && (!model.equals(target.get("baseModel")) || !revision.equals(target.get("revision")))) return null;
        var result = new LinkedHashMap<String, Object>();
        METADATA.forEach(key -> result.put(key, data.get(key)));
        return result;
    }

    private static void safeFields(Map<String, Object> supplied, Map<String, Object> target) {
        if (supplied.get("progress") instanceof Number number && number.doubleValue() >= 0 && number.doubleValue() <= 100) target.put("progress", number.intValue());
        if (supplied.get("available") instanceof Boolean available) target.put("available", available);
        for (String key : Set.of("errorCode", "reasonCode")) if (supplied.get(key) instanceof String code && code.matches("[A-Z][A-Z0-9_]{0,59}")) target.put(key, code);
        for (String key : Set.of("createdAt", "updatedAt")) if (supplied.get(key) instanceof String time && time.matches("[0-9T:.+Z\\-]{10,40}")) target.put(key, time);
    }

    private static Map<String, Object> base(UUID id, Map<String, Object> origin, String status) {
        var value = new LinkedHashMap<String, Object>();
        value.put("jobId", id.toString()); value.put("trainingJobId", id.toString());
        value.put("model", runtimeModel(id)); value.put("sourceModel", source(origin)); value.put("provider", "ollama");
        value.put("trainedModel", true); value.put("qualityEvaluated", false); value.put("status", status);
        value.put("message", switch (status) {
            case "NOT_BUILT" -> "완료한 학습을 기반 모델과 합쳐 새 분석용 모델을 만들 수 있습니다. 학습 서버에서 변환 도구와 저장 공간을 확인합니다.";
            case "PENDING" -> "새 모델 생성 요청을 접수했습니다.";
            case "FUSING" -> "학습 서버에서 학습 결과를 기반 모델에 합치고 있습니다.";
            case "CONVERTING" -> "학습 서버에서 완성 모델을 분석 환경의 모델 형식으로 변환하고 있습니다.";
            case "QUANTIZING" -> "학습 서버에서 새 모델의 실행용 가중치를 준비하고 있습니다.";
            case "PUBLISHING" -> "완성 모델을 검증하고 공유 저장소에 게시하고 있습니다.";
            case "SUCCEEDED" -> "분석용 새 모델을 만들었습니다. 분석 서버에 등록한 뒤 응답 검증과 적용을 진행하세요.";
            case "CANCELLED" -> "새 모델 생성을 취소했습니다. 완료한 학습 원본은 보존합니다.";
            case "IMPORTING" -> "완성된 새 모델을 분석 서버의 기존 모델 실행 환경에 등록하고 있습니다.";
            case "READY" -> "새 모델을 등록했습니다. JSON 응답을 검증한 뒤 새 분석에 적용할 수 있습니다.";
            case "WAITING" -> "공유 저장소의 새 모델 파일이 모두 동기화되기를 기다립니다. 동기화 후 등록을 다시 요청하세요.";
            case "NOT_STARTED" -> "이 학습 결과의 새 모델은 아직 분석 서버에 등록하지 않았습니다.";
            case "UNKNOWN" -> "원격 요청 결과를 확인하지 못했습니다. 같은 작업의 상태를 조회한 뒤 다시 요청하세요.";
            default -> "새 모델 준비를 완료하지 못했습니다. 오류 코드와 학습 서버의 변환 환경·저장 공간을 확인하세요.";
        });
        return value;
    }
    private static Map<String, Object> unavailable(UUID id, Map<String, Object> origin, String code) { var value = base(id, origin, "UNAVAILABLE"); value.put("errorCode", code); return value; }
    private static Map<String, Object> importUnavailable(UUID id, Map<String, Object> origin, String code) {
        var value = unavailable(id, origin, code);
        value.put("message", importFailureMessage(code));
        return value;
    }
    private static Map<String, Object> importFailure(UUID id, Map<String, Object> origin, ModelExecutionClient.RemoteFailure failure) {
        if (!failure.uncertain) return importUnavailable(id, origin, failure.code);
        var value = base(id, origin, "UNKNOWN");
        value.put("errorCode", failure.code);
        return value;
    }
    private static String importFailureMessage(String code) {
        return switch (code == null ? "" : code) {
            case "TRAINED_MODEL_IMPORT_TIMEOUT" -> "모델 등록 제한 시간이 지났습니다. 공유 파일의 다운로드와 설치 목록을 확인한 뒤 등록 상태를 다시 확인하세요.";
            case "TRAINED_MODEL_CLOUD_DOWNLOAD_FAILED", "TRAINED_MODEL_CLOUD_DOWNLOAD_UNAVAILABLE" -> "AI 분석/추론 서버에서 공유 모델 파일의 다운로드를 완료하지 못했습니다. 공유 저장소 연결·접근 권한·저장 공간을 확인한 뒤 다시 등록하세요.";
            case "TRAINED_MODEL_CLOUD_PATH_INVALID" -> "공유 저장소의 모델 폴더와 필수 파일 구성을 확인한 뒤 다시 등록하세요.";
            case "TRAINED_MODEL_IMPORT_INTERRUPTED" -> "AI 분석/추론 서버에서 모델 등록이 중단됐습니다. 등록 상태를 확인한 뒤 다시 요청하세요.";
            case "TRAINED_MODEL_DISK_FULL" -> "AI 분석/추론 서버의 모델 보관과 등록에 필요한 저장 공간이 부족합니다. 공간을 확보한 뒤 다시 등록하세요.";
            case "TRAINED_MODEL_INTEGRITY_FAILED" -> "공유 모델 파일의 서명·크기·해시를 확인하지 못했습니다. 파일 동기화와 원본 구성을 확인하세요.";
            case "TRAINED_MODEL_IMPORT_BUSY" -> "AI 분석/추론 서버에서 다른 모델을 등록 중입니다. 완료 후 다시 요청하세요.";
            case "TRAINED_MODEL_NAME_CONFLICT", "TRAINED_MODEL_IMPORT_CONFLICT" -> "같은 이름이나 버전의 다른 모델이 있어 등록하지 못했습니다. 설치 모델과 등록 상태를 확인하세요.";
            case "TRAINED_MODEL_SOURCE_UNAVAILABLE", "TRAINED_MODEL_TEMPLATE_INVALID" -> "AI 분석/추론 서버에서 기반 모델과 대화 형식을 확인하지 못했습니다. 기반 모델의 설치 상태를 확인하세요.";
            case "TRAINED_MODEL_NOT_CONFIGURED", "TRAINED_MODEL_STATE_UNAVAILABLE", "TRAINED_MODEL_STATE_INVALID" -> "AI 분석/추론 서버의 공유 폴더·모델 보관 폴더·서명 검증 설정을 확인하세요.";
            default -> "모델 등록을 완료하지 못했습니다. 오류 코드와 AI 분석/추론 서버의 파일 동기화·모델 실행 환경을 확인하세요.";
        };
    }
    private static Map<String, Object> failure(UUID id, Map<String, Object> origin, ModelExecutionClient.RemoteFailure failure) {
        boolean preparation = Set.of("MODEL_BUILD_UNAVAILABLE", "MODEL_BUILD_DISK_SPACE", "MODEL_BUILD_BUSY").contains(failure.code);
        var value = base(id, origin, !preparation && failure.uncertain ? "UNKNOWN" : "UNAVAILABLE");
        value.put("errorCode", failure.code);
        if (preparation) { value.put("available", false); value.put("message", readinessMessage(failure.code)); }
        return value;
    }
    private static String readinessMessage(String code) {
        if ("MODEL_BUILD_DISK_SPACE".equals(code)) return "학습 서버의 모델 작업 폴더에 180 GiB 이상의 여유 공간이 필요합니다. 공간을 확보한 뒤 상태를 다시 확인하세요.";
        if ("MODEL_BUILD_BUSY".equals(code)) return "학습 서버에서 학습이나 새 모델 생성이 진행 중입니다. 완료하거나 취소한 뒤 다시 확인하세요.";
        return "학습 서버의 MLX 실행 환경과 고정 버전 변환 도구 경로·양자화 실행 파일 해시를 설정한 뒤 상태를 다시 확인하세요. 분석 서버의 MLX 설정은 필요하지 않습니다.";
    }
    private static String source(Map<String, Object> row) {
        if (row.get("result") instanceof Map<?, ?> data && data.get("target") instanceof Map<?, ?> target && target.get("sourceModel") instanceof String model && validModel(model)) return model;
        return row.get("request") instanceof Map<?, ?> request && request.get("model") instanceof String model && validModel(model) ? model : null;
    }
    private static String buildPath(UUID id) { return "/api/training/jobs/" + id + "/model-build"; }
    private static String runtimeModel(UUID id) { return "doenaai-trained:web-" + id; }
    private static boolean validModel(String model) { return model.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,199}") && !model.contains("://") && !model.contains(".."); }
    private static boolean hash(Object value) { return value instanceof String text && text.matches("[0-9a-f]{64}"); }
    private static void empty(Map<String, Object> body) { if (body == null || !body.isEmpty()) throw new ModelCatalogException(HttpStatus.BAD_REQUEST, "모델 준비 요청에는 학습 작업 식별자만 사용할 수 있습니다."); }
}
