package com.doenaai.model.controller;

import com.doenaai.auth.security.PermissionChecker;
import com.doenaai.model.dto.ModelEvaluationRequest;
import com.doenaai.model.dto.ModelVersionCreateRequest;
import com.doenaai.model.service.ModelCatalogService;
import com.doenaai.model.service.ModelHistorySyncService;
import com.doenaai.model.service.ModelRuntimeClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 실제 메서드 보안 프록시를 거쳐 조회와 쓰기 권한을 분리한다. */
class ModelControllerSecurityTest {
    private AnnotationConfigApplicationContext context;
    private ModelController controller;
    private PermissionChecker permissions;
    private ModelCatalogService catalog;
    private ModelHistorySyncService history;
    private Jwt jwt;

    @BeforeEach void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        controller = context.getBean(ModelController.class);
        permissions = context.getBean(PermissionChecker.class);
        catalog = context.getBean(ModelCatalogService.class);
        history = context.getBean(ModelHistorySyncService.class);
        jwt = Jwt.withTokenValue("synthetic-token").header("alg", "none").subject(UUID.randomUUID().toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); context.close(); }

    @Test void noModelPermissionsDenyEveryEndpointBeforeServices() {
        assertThatThrownBy(() -> controller.catalog()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.runtime()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.version(UUID.randomUUID())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.create(request(), jwt)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.evaluate(UUID.randomUUID(), new ModelEvaluationRequest("PASSED", null, "확인", Map.of()), jwt))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.synchronizeHistory(Map.of())).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(catalog, history, context.getBean(ModelRuntimeClient.class));
    }

    @Test void readPermissionAllowsAllGetEndpointsButNeverRegistrationOrEvaluation() {
        when(permissions.has(any(), eq("MODEL_READ"))).thenReturn(true);
        when(catalog.catalog()).thenReturn(Map.of());
        when(catalog.version(any())).thenReturn(Map.of());
        when(context.getBean(ModelRuntimeClient.class).status()).thenReturn(Map.of());
        assertThat(controller.catalog().getStatusCode().value()).isEqualTo(200);
        assertThat(controller.version(UUID.randomUUID()).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.runtime().getStatusCode().value()).isEqualTo(200);
        assertThatThrownBy(() -> controller.create(request(), jwt)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.evaluate(UUID.randomUUID(), null, jwt)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.synchronizeHistory(Map.of())).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(history);
    }

    @Test void trainOrDeployPermissionAllowsRegistrationButNotManualEvaluation() {
        when(catalog.createVersion(any(), any())).thenReturn(Map.of());
        for (String permission : new String[]{"MODEL_TRAIN", "MODEL_DEPLOY"}) {
            reset(permissions);
            when(permissions.has(any(), eq(permission))).thenReturn(true);
            assertThat(controller.create(request(), jwt).getStatusCode().value()).isEqualTo(201);
            assertThatThrownBy(() -> controller.evaluate(UUID.randomUUID(), null, jwt)).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test void evaluatePermissionAllowsOnlyEvaluationAndPassesAuthenticatedActor() {
        when(permissions.has(any(), eq("MODEL_EVALUATE"))).thenReturn(true);
        when(catalog.recordEvaluation(any(), any(), any())).thenReturn(Map.of());
        UUID id = UUID.randomUUID();
        var request = new ModelEvaluationRequest("PASSED", null, "수동 확인", Map.of());
        assertThat(controller.evaluate(id, request, jwt).getStatusCode().value()).isEqualTo(201);
        verify(catalog).recordEvaluation(id, request, UUID.fromString(jwt.getSubject()));
        assertThatThrownBy(() -> controller.catalog()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.create(request(), jwt)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void onlyDeployPermissionAllowsHistorySynchronization() {
        when(history.synchronize()).thenReturn(Map.of("status", "SUCCEEDED"));
        for (String permission : new String[]{"MODEL_READ", "MODEL_TRAIN", "MODEL_EVALUATE"}) {
            reset(permissions);
            when(permissions.has(any(), eq(permission))).thenReturn(true);
            assertThatThrownBy(() -> controller.synchronizeHistory(Map.of())).isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(history);
        reset(permissions);
        when(permissions.has(any(), eq("MODEL_DEPLOY"))).thenReturn(true);
        assertThat(controller.synchronizeHistory(Map.of()).getStatusCode().value()).isEqualTo(200);
        verify(history).synchronize();
        verifyNoInteractions(catalog, context.getBean(ModelRuntimeClient.class));
    }

    private static ModelVersionCreateRequest request() {
        return new ModelVersionCreateRequest(null, "분석 모델", null, null, "example/model", "v1", "LORA", null, null, null, null, null);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Config {
        @Bean PermissionChecker permissionChecker() { return mock(PermissionChecker.class); }
        @Bean ModelCatalogService catalog() { return mock(ModelCatalogService.class); }
        @Bean ModelRuntimeClient runtime() { return mock(ModelRuntimeClient.class); }
        @Bean ModelHistorySyncService history() { return mock(ModelHistorySyncService.class); }
        @Bean ModelController controller(ModelCatalogService catalog, ModelRuntimeClient runtime, ModelHistorySyncService history) {
            return new ModelController(catalog, runtime, history);
        }
    }
}
