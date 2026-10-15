package com.doenaai.analysis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** 분석 요청을 받은 백엔드 실행 인스턴스와 작업 대상을 연결한다. */
@Component
public class AnalysisNodeIdentity {
    private final boolean workerEnabled;
    private final String instanceId;

    @Autowired
    public AnalysisNodeIdentity(@Value("${doenaai.analysis.worker-enabled:true}") boolean workerEnabled) {
        this(workerEnabled, UUID.randomUUID().toString());
    }

    public AnalysisNodeIdentity(boolean workerEnabled, String instanceId) {
        if (instanceId == null || instanceId.isBlank() || instanceId.length() > 80) {
            throw new IllegalArgumentException("분석 인스턴스 ID가 올바르지 않습니다.");
        }
        this.workerEnabled = workerEnabled;
        this.instanceId = instanceId;
    }

    public String instanceId() {
        return instanceId;
    }

    public void requireLocalWorker() {
        if (!workerEnabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "이 백엔드에서는 분석 작업을 처리할 수 없습니다. 분석 워커 설정을 확인하세요.");
        }
    }
}
