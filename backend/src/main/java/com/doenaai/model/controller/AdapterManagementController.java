package com.doenaai.model.controller;

import com.doenaai.model.dto.AdapterPrepareRequest;
import com.doenaai.model.service.AdapterManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** 학습 결과의 파일 준비만 요청한다. 응답 검증과 운영 전환은 기존 실행 API를 사용한다. */
@RestController
@RequestMapping("/api/models/adapters")
@RequiredArgsConstructor
public class AdapterManagementController {
    private final AdapterManagementService adapters;

    @PostMapping("/{trainingJobId}/prepare")
    @PreAuthorize("@permissionChecker.has(authentication, 'MODEL_DEPLOY')")
    public ResponseEntity<Map<String, Object>> prepare(@PathVariable UUID trainingJobId,
            @RequestBody(required = false) AdapterPrepareRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(adapters.prepare(trainingJobId));
    }
}
