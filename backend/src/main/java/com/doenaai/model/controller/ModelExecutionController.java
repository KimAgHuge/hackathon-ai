package com.doenaai.model.controller;

import com.doenaai.model.dto.ModelExecutionRequest;
import com.doenaai.model.service.ModelExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** 모델 실행 권한과 조회 권한을 나누고 요청자는 로그인 정보에서만 결정한다. */
@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class ModelExecutionController {
    private final ModelExecutionService execution;

    @GetMapping("/installed")
    @PreAuthorize("@permissionChecker.has(authentication, 'MODEL_READ')")
    public ResponseEntity<Map<String, Object>> installed() { return response(execution.installed()); }

    @PostMapping("/probe")
    @PreAuthorize("@permissionChecker.has(authentication, 'MODEL_DEPLOY')")
    public ResponseEntity<Map<String, Object>> probe(@RequestBody ModelExecutionRequest body, @AuthenticationPrincipal Jwt jwt) {
        return response(execution.execute("MODEL_PROBE", body, actor(jwt), null));
    }

    @PostMapping("/activate")
    @PreAuthorize("@permissionChecker.has(authentication, 'MODEL_DEPLOY')")
    public ResponseEntity<Map<String, Object>> activate(@RequestBody ModelExecutionRequest body, @AuthenticationPrincipal Jwt jwt) {
        return response(execution.execute("MODEL_ACTIVATE", body, actor(jwt), null));
    }

    @PostMapping("/rollback")
    @PreAuthorize("@permissionChecker.has(authentication, 'MODEL_ROLLBACK')")
    public ResponseEntity<Map<String, Object>> rollback(@RequestBody ModelExecutionRequest body, @AuthenticationPrincipal Jwt jwt) {
        return response(execution.execute("MODEL_ROLLBACK", body, actor(jwt), null));
    }

    @GetMapping("/training")
    @PreAuthorize("@permissionChecker.has(authentication, 'MODEL_READ')")
    public ResponseEntity<Map<String, Object>> training(@RequestParam(required = false) String model) { return response(execution.training(model)); }

    @PostMapping("/training")
    @PreAuthorize("@permissionChecker.has(authentication, 'MODEL_TRAIN')")
    public ResponseEntity<Map<String, Object>> train(@RequestBody ModelExecutionRequest body, @AuthenticationPrincipal Jwt jwt) {
        return response(execution.execute("TRAINING_START", body, actor(jwt), null));
    }

    @PostMapping("/training/{id}/cancel")
    @PreAuthorize("@permissionChecker.has(authentication, 'MODEL_TRAIN')")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable UUID id, @RequestBody ModelExecutionRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        return response(execution.execute("TRAINING_CANCEL", body, actor(jwt), id));
    }

    @GetMapping("/operations")
    @PreAuthorize("@permissionChecker.has(authentication, 'MODEL_READ')")
    public ResponseEntity<Map<String, Object>> operations(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size, @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "") String status) {
        return response(execution.operations(page, size, query, status));
    }

    @GetMapping("/operations/{id}")
    @PreAuthorize("@permissionChecker.has(authentication, 'MODEL_READ')")
    public ResponseEntity<Map<String, Object>> operation(@PathVariable UUID id) { return response(execution.operation(id)); }

    private static UUID actor(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (RuntimeException ignored) { throw new AccessDeniedException("유효한 사용자 인증이 필요합니다."); }
    }

    private static ResponseEntity<Map<String, Object>> response(Map<String, Object> value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
