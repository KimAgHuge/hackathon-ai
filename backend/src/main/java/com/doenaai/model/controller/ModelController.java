package com.doenaai.model.controller;

import com.doenaai.model.dto.ModelEvaluationRequest;
import com.doenaai.model.dto.ModelVersionCreateRequest;
import com.doenaai.model.service.ModelCatalogService;
import com.doenaai.model.service.ModelRuntimeClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class ModelController {
    private final ModelCatalogService catalog;
    private final ModelRuntimeClient runtime;

    @GetMapping
    @PreAuthorize("@permissionChecker.has(authentication, 'MODEL_READ')")
    public ResponseEntity<Map<String, Object>> catalog() {
        return response(HttpStatus.OK, catalog.catalog());
    }

    @GetMapping("/runtime")
    @PreAuthorize("@permissionChecker.has(authentication, 'MODEL_READ')")
    public ResponseEntity<Map<String, Object>> runtime() {
        return response(HttpStatus.OK, runtime.status());
    }

    @GetMapping("/versions/{id}")
    @PreAuthorize("@permissionChecker.has(authentication, 'MODEL_READ')")
    public ResponseEntity<Map<String, Object>> version(@PathVariable UUID id) {
        return response(HttpStatus.OK, catalog.version(id));
    }

    @PostMapping("/versions")
    @PreAuthorize("@permissionChecker.has(authentication, 'MODEL_TRAIN') or @permissionChecker.has(authentication, 'MODEL_DEPLOY')")
    public ResponseEntity<Map<String, Object>> create(@RequestBody ModelVersionCreateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return response(HttpStatus.CREATED, catalog.createVersion(request, userId(jwt)));
    }

    @PostMapping("/versions/{id}/evaluations")
    @PreAuthorize("@permissionChecker.has(authentication, 'MODEL_EVALUATE')")
    public ResponseEntity<Map<String, Object>> evaluate(@PathVariable UUID id, @RequestBody ModelEvaluationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return response(HttpStatus.CREATED, catalog.recordEvaluation(id, request, userId(jwt)));
    }

    private static UUID userId(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (RuntimeException exception) { throw new AccessDeniedException("유효한 사용자 인증이 필요합니다."); }
    }

    private static ResponseEntity<Map<String, Object>> response(HttpStatus status, Map<String, Object> value) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(value);
    }
}
