package com.doenaai.model.controller;

import com.doenaai.model.service.TrainedModelManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** 새 모델 생성은 학습 서버에, 완성 모델 등록은 분석 서버에 요청한다. */
@RestController
@RequestMapping("/api/models/trained")
@RequiredArgsConstructor
public class TrainedModelManagementController {
    private final TrainedModelManagementService models;

    @GetMapping("/{id}/build")
    @PreAuthorize("@permissionChecker.has(authentication, 'MODEL_READ')")
    public ResponseEntity<Map<String, Object>> buildStatus(@PathVariable UUID id) { return response(models.build(id, "GET", Map.of())); }

    @PostMapping("/{id}/build")
    @PreAuthorize("@permissionChecker.has(authentication, 'MODEL_TRAIN')")
    public ResponseEntity<Map<String, Object>> build(@PathVariable UUID id, @RequestBody Map<String, Object> body) { return response(models.build(id, "POST", body)); }

    @PostMapping("/{id}/build/cancel")
    @PreAuthorize("@permissionChecker.has(authentication, 'MODEL_TRAIN')")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable UUID id, @RequestBody Map<String, Object> body) { return response(models.cancel(id, body)); }

    @PostMapping("/{id}/import")
    @PreAuthorize("@permissionChecker.has(authentication, 'MODEL_DEPLOY')")
    public ResponseEntity<Map<String, Object>> register(@PathVariable UUID id, @RequestBody Map<String, Object> body) { return response(models.register(id, body)); }

    @GetMapping("/{id}/import")
    @PreAuthorize("@permissionChecker.has(authentication, 'MODEL_READ')")
    public ResponseEntity<Map<String, Object>> importStatus(@PathVariable UUID id) { return response(models.importStatus(id)); }

    private static ResponseEntity<Map<String, Object>> response(Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
