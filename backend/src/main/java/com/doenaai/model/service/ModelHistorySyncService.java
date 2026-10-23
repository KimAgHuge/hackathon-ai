package com.doenaai.model.service;

import com.doenaai.model.repository.ModelOperationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 학습이나 모델 전환을 다시 실행하지 않고 누락된 관리 기록만 보완한다. */
@Service
@RequiredArgsConstructor
public class ModelHistorySyncService {
    private final ModelOperationRepository repository;
    private final ModelExecutionClient client;
    private final TrainedModelManagementService trained;
    private final ModelExecutionService execution;

    public Map<String, Object> synchronize() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        int registrations = 0, deployments = 0, failures = 0;
        var training = repository.historyTraining(client.targetKey(true));
        var transitions = repository.historyTransitions(client.targetKey(false));
        int attempted = 0;
        for (var row : training) {
            if (attempted >= 100 || System.nanoTime() >= deadline) { failures++; break; }
            attempted++;
            try {
                var result = trained.importStatus(UUID.fromString((String) row.get("id")));
                if ("READY".equals(result.get("status"))) {
                    registrations++;
                    if (Boolean.TRUE.equals(result.get("historySyncRequired"))) failures++;
                } else if (Set.of("UNKNOWN", "UNAVAILABLE", "FAILED").contains(String.valueOf(result.get("status")))) failures++;
            } catch (RuntimeException failure) { failures++; }
        }
        for (var row : transitions) {
            if (attempted >= 100 || System.nanoTime() >= deadline) { failures++; break; }
            attempted++;
            try {
                var result = execution.operation(UUID.fromString((String) row.get("id")));
                if ("APPLIED".equals(result.get("status")) || "ROLLED_BACK".equals(result.get("status"))) {
                    deployments++;
                    if (Boolean.TRUE.equals(result.get("historySyncRequired"))) failures++;
                } else failures++;
            } catch (RuntimeException failure) { failures++; }
        }
        return Map.of("status", failures == 0 ? "SUCCEEDED" : "PARTIAL", "registrationsChecked", registrations,
                "deploymentsChecked", deployments, "failures", failures,
                "message", failures == 0 ? "모델 버전과 배포 이력을 동기화했습니다. 학습과 모델 적용은 다시 실행하지 않았습니다."
                        : "확인 가능한 기록을 동기화했습니다. 보완하지 못한 기록은 연결 상태를 확인한 뒤 기록 동기화를 다시 요청해 주세요.");
    }
}
