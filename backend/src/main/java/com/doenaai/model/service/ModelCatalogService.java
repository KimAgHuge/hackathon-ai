package com.doenaai.model.service;

import com.doenaai.model.dto.ModelEvaluationRequest;
import com.doenaai.model.dto.ModelVersionCreateRequest;
import com.doenaai.model.repository.ModelCatalogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 모델 관리 기록은 실제 학습 실행과 추론 서버의 모델 전환 권한을 뜻하지 않는다. */
@Service
@RequiredArgsConstructor
public class ModelCatalogService {
    private static final int LIMIT = 100;
    private static final String EXECUTION_REASON = "등록 파일의 자동 수신·변환·Ollama 등록과 직접 배포는 지원하지 않습니다. 설치된 Ollama 모델의 전환과 학습 실행은 별도 실행 영역을 사용해 주세요. 등록과 수동 평가는 관리 기록만 저장합니다.";
    private final ModelCatalogRepository repository;

    @Transactional(readOnly = true)
    public Map<String, Object> catalog() {
        var models = repository.models(LIMIT + 1);
        var versions = repository.versions(LIMIT + 1);
        var jobs = repository.trainingJobs(LIMIT + 1);
        var deployments = repository.deployments(LIMIT + 1);
        return Map.of("models", bounded(models), "versions", bounded(versions), "trainingJobs", bounded(jobs),
                "deployments", bounded(deployments), "limit", LIMIT,
                "truncated", Map.of("models", models.size() > LIMIT, "versions", versions.size() > LIMIT,
                    "trainingJobs", jobs.size() > LIMIT, "deployments", deployments.size() > LIMIT),
                "capabilities", Map.of("trainingExecution", false, "deploymentExecution", false,
                    "rollbackExecution", false, "reason", EXECUTION_REASON));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> version(UUID id) {
        var value = new LinkedHashMap<>(repository.version(id).orElseThrow(ModelCatalogService::notFound));
        var evaluations = repository.evaluations(id);
        var deployments = repository.versionDeployments(id);
        value.put("evaluations", bounded(evaluations));
        value.put("deployments", bounded(deployments));
        value.put("detailTruncated", Map.of("evaluations", evaluations.size() > LIMIT, "deployments", deployments.size() > LIMIT));
        value.put("readiness", Map.of("canDeploy", false, "canRollback", false, "reason", EXECUTION_REASON,
                "checks", List.of(
                    check("fileReceived", "파일 수신", "NAS 경로는 등록 정보이며 파일 존재와 수신 완료를 확인하지 않았습니다."),
                    check("checksumVerified", "파일 해시 검증", "입력한 해시를 실제 파일과 대조하는 기능이 연결되지 않았습니다."),
                    check("compatibility", "실행 환경 호환성", "추론 환경에서 파일을 불러올 수 있는지 검증하지 않았습니다."),
                    check("automaticEvaluation", "자동 평가", "수동 통과 기록은 자동 평가 실행과 통과를 대신하지 않습니다."),
                    check("approval", "배포 승인", "배포 승인 흐름이 연결되지 않았습니다."),
                    check("runtimeSwitch", "등록 파일 직접 적용", "설치된 Ollama 모델의 전환·복구는 별도 영역을 사용합니다. 이 등록 파일을 직접 적용하는 기능은 아닙니다."))));
        return value;
    }

    /** 새 논리 모델과 첫 버전 저장은 같은 트랜잭션에서 성공하거나 함께 되돌린다. */
    @Transactional
    public Map<String, Object> createVersion(ModelVersionCreateRequest input, UUID userId) {
        var request = ModelInputPolicy.version(input);
        UUID modelId = request.modelId();
        if (modelId != null && !repository.modelExists(modelId)) throw notFound();
        UUID id = UUID.randomUUID();
        try {
            if (modelId == null) {
                modelId = UUID.randomUUID();
                repository.createModel(modelId, request, userId);
            }
            repository.createVersion(id, modelId, request, userId);
        } catch (DuplicateKeyException exception) {
            throw new ModelCatalogException(HttpStatus.CONFLICT, "같은 모델 이름 또는 모델 버전이 이미 등록되어 있습니다.");
        }
        var value = new LinkedHashMap<>(version(id));
        value.put("message", "READY는 등록 상태입니다. 모델 파일의 수신, 해시 검증 또는 배포 준비 완료를 뜻하지 않습니다.");
        return value;
    }

    /** 평가 메타데이터만 추가하며 버전 상태나 배포 상태를 변경하지 않는다. */
    @Transactional
    public Map<String, Object> recordEvaluation(UUID versionId, ModelEvaluationRequest input, UUID userId) {
        var request = ModelInputPolicy.evaluation(input);
        repository.version(versionId).orElseThrow(ModelCatalogService::notFound);
        UUID id = UUID.randomUUID();
        repository.createEvaluation(id, versionId, request, userId);
        var value = new LinkedHashMap<>(version(versionId));
        value.put("message", "수동 평가 기록을 저장했습니다. 평가를 실행하거나 모델을 자동 검증·배포하지 않았습니다.");
        return value;
    }

    private static List<Map<String, Object>> bounded(List<Map<String, Object>> values) {
        return List.copyOf(values.subList(0, Math.min(values.size(), LIMIT)));
    }

    private static Map<String, Object> check(String key, String label, String message) {
        return Map.of("key", key, "label", label, "status", "UNAVAILABLE", "message", message);
    }

    private static ModelCatalogException notFound() {
        return new ModelCatalogException(HttpStatus.NOT_FOUND, "모델 또는 모델 버전을 찾을 수 없습니다.");
    }
}
