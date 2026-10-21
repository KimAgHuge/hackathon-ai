package com.doenaai.model.service;

import com.doenaai.model.repository.ModelOperationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 학습 결과를 게시하고 분석 서버의 준비 상태를 확인한다. 추론 실행과 모델 전환은 하지 않는다. */
@Service
@RequiredArgsConstructor
public class AdapterManagementService {
    private final ModelOperationRepository repository;
    private final ModelExecutionClient client;

    public Map<String, Object> prepare(UUID trainingJobId) {
        var origin = repository.find(trainingJobId).orElseThrow(() -> new ModelCatalogException(HttpStatus.NOT_FOUND, "학습 실행 기록을 찾을 수 없습니다."));
        if (!"TRAINING_START".equals(origin.get("action")) || !client.targetKey(true).equals(origin.get("targetKey")))
            throw new ModelCatalogException(HttpStatus.CONFLICT, "현재 학습 서버에서 실행한 작업만 준비할 수 있습니다. 학습 이력을 새로고침해 주세요.");
        String expectedSource = sourceModel(origin);
        if (expectedSource == null) throw new ModelCatalogException(HttpStatus.CONFLICT, "학습 작업의 기반 모델 정보가 없습니다. 학습 결과를 확인해 주세요.");
        try {
            var delivery = client.training("POST", "/api/training/jobs/" + trainingJobId + "/delivery", Map.of());
            if (!validDelivery(delivery, trainingJobId, expectedSource, origin))
                return result("UNAVAILABLE", trainingJobId, expectedSource, "ADAPTER_METADATA_INVALID", null);
            var metadata = new LinkedHashMap<String, Object>();
            for (String key : Set.of("jobId", "version", "manifestSha256", "sourceModel", "baseModel", "baseRevision")) metadata.put(key, delivery.get(key));
            // 동일 학습 작업을 다시 요청해도 양쪽 서버가 같은 결과를 사용하며 새 학습은 만들지 않는다.
            var imported = client.inference("POST", "/api/models/adapters/import", metadata, false);
            if (!validImport(imported, trainingJobId, expectedSource, (String) delivery.get("manifestSha256")))
                return result("UNAVAILABLE", trainingJobId, expectedSource, "ADAPTER_IMPORT_RESPONSE_INVALID", null);
            String status = (String) imported.get("status");
            var response = result(status, trainingJobId, expectedSource, code(imported.get("errorCode")), (String) imported.get("digest"));
            response.put("manifestSha256", delivery.get("manifestSha256"));
            return response;
        } catch (ModelExecutionClient.RemoteFailure failure) {
            return result(failure.uncertain ? "UNKNOWN" : "UNAVAILABLE", trainingJobId, expectedSource, failure.code, null);
        }
    }

    private static boolean validDelivery(Map<String, Object> supplied, UUID id, String source, Map<String, Object> origin) {
        if (supplied == null || !"PUBLISHED".equals(supplied.get("status")) || !id.toString().equals(supplied.get("jobId"))
                || !("web-" + id).equals(supplied.get("version")) || !digest(supplied.get("manifestSha256"))
                || !source.equals(supplied.get("sourceModel")) || !model(supplied.get("baseModel"))
                || !(supplied.get("baseRevision") instanceof String revision) || !revision.matches("[0-9a-f]{40}")) return false;
        if (origin.get("result") instanceof Map<?, ?> result && result.get("target") instanceof Map<?, ?> target)
            return supplied.get("baseModel").equals(target.get("baseModel")) && revision.equals(target.get("revision"));
        return true;
    }

    private static boolean validImport(Map<String, Object> supplied, UUID id, String source, String manifestSha256) {
        return supplied != null && supplied.get("status") instanceof String state && Set.of("READY", "WAITING", "UNAVAILABLE").contains(state)
                && ("doenaai-mlx:web-" + id).equals(supplied.get("model")) && digest(supplied.get("digest"))
                && manifestSha256.equals(supplied.get("digest"))
                && id.toString().equals(supplied.get("adapterId")) && source.equals(supplied.get("sourceModel"));
    }

    private static Map<String, Object> result(String status, UUID id, String source, String errorCode, String digest) {
        var result = new LinkedHashMap<String, Object>();
        result.put("status", status); result.put("trainingJobId", id.toString()); result.put("adapterId", id.toString());
        result.put("sourceModel", source); result.put("provider", "mlx"); result.put("qualityEvaluated", false);
        result.put("model", "doenaai-mlx:web-" + id);
        if (digest != null) result.put("digest", digest);
        if (errorCode != null) result.put("errorCode", errorCode);
        result.put("message", switch (status) {
            case "READY" -> "학습 어댑터 파일을 준비했습니다. JSON 응답을 검증한 뒤 분석 모델로 전환할 수 있습니다.";
            case "WAITING" -> "학습 결과가 공유 저장소에 게시됐습니다. 분석 서버의 파일 동기화를 기다린 뒤 다시 준비해 주세요.";
            case "UNKNOWN" -> "원격 준비 결과를 확인하지 못했습니다. 같은 학습 작업에서 다시 준비해 상태를 확인해 주세요.";
            default -> "학습 어댑터를 준비하지 못했습니다. 오류 코드와 학습 서버·분석 서버 설정을 확인해 주세요.";
        });
        return result;
    }

    private static String sourceModel(Map<String, Object> origin) {
        if (origin.get("result") instanceof Map<?, ?> result && result.get("target") instanceof Map<?, ?> target && model(target.get("sourceModel")))
            return (String) target.get("sourceModel");
        if (origin.get("request") instanceof Map<?, ?> request && model(request.get("model"))) return (String) request.get("model");
        return null;
    }

    private static boolean model(Object value) {
        return value instanceof String text && text.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,199}") && !text.contains("://") && !text.contains("..");
    }
    private static boolean digest(Object value) { return value instanceof String text && text.matches("[0-9a-f]{64}"); }
    private static String code(Object value) { return value instanceof String text && text.matches("[A-Z][A-Z0-9_]{0,59}") ? text : null; }
}
