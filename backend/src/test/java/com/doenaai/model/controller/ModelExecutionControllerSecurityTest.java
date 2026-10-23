package com.doenaai.model.controller;

import com.doenaai.auth.security.PermissionChecker;
import com.doenaai.model.dto.ModelExecutionRequest;
import com.doenaai.model.service.ModelExecutionService;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
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

/** 실제 메서드 보안 프록시에서 읽기·학습·배포·복구 권한을 각각 검사한다. */
class ModelExecutionControllerSecurityTest {
    private AnnotationConfigApplicationContext context;
    private ModelExecutionController controller;
    private ModelExecutionService service;
    private PermissionChecker permissions;
    private Jwt jwt;
    private final UUID id = UUID.randomUUID();
    private final ModelExecutionRequest request = new ModelExecutionRequest(id, "example:1b", "a".repeat(64), "test_probe_token_123456", 0L, 10);

    @BeforeEach void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        controller = context.getBean(ModelExecutionController.class);
        service = context.getBean(ModelExecutionService.class);
        permissions = context.getBean(PermissionChecker.class);
        jwt = Jwt.withTokenValue("synthetic-token").header("alg", "none").subject(UUID.randomUUID().toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        when(service.execute(anyString(), any(), any(), nullable(UUID.class))).thenReturn(Map.of("status", "PENDING"));
    }

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); context.close(); }

    @Test void noPermissionDeniesAllEndpointsBeforeAnyServiceCall() {
        assertThatThrownBy(() -> controller.installed()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.training(null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.operations(0, 10, "", "")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.operation(id)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.probe(request, jwt)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.activate(request, jwt)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.rollback(request, jwt)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.train(request, jwt)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.cancel(id, request, jwt)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(service);
    }

    @Test void readPermissionNeverAllowsMutation() {
        when(permissions.has(any(), eq("MODEL_READ"))).thenReturn(true);
        assertThat(controller.installed().getStatusCode().value()).isEqualTo(200);
        assertThat(controller.training(null).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.operations(0, 10, "", "").getStatusCode().value()).isEqualTo(200);
        assertThatThrownBy(() -> controller.activate(request, jwt)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.train(request, jwt)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.rollback(request, jwt)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void eachExecutionPermissionIsSeparatedAndActorAlwaysComesFromJwt() {
        when(permissions.has(any(), eq("MODEL_TRAIN"))).thenReturn(true);
        controller.train(request, jwt); controller.cancel(id, request, jwt);
        verify(service).execute("TRAINING_START", request, UUID.fromString(jwt.getSubject()), null);
        assertThatThrownBy(() -> controller.activate(request, jwt)).isInstanceOf(AccessDeniedException.class);
        reset(permissions);
        when(permissions.has(any(), eq("MODEL_DEPLOY"))).thenReturn(true);
        controller.probe(request, jwt); controller.activate(request, jwt);
        assertThatThrownBy(() -> controller.train(request, jwt)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.rollback(request, jwt)).isInstanceOf(AccessDeniedException.class);
        reset(permissions);
        when(permissions.has(any(), eq("MODEL_ROLLBACK"))).thenReturn(true);
        controller.rollback(request, jwt);
        assertThatThrownBy(() -> controller.probe(request, jwt)).isInstanceOf(AccessDeniedException.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Config {
        @Bean PermissionChecker permissionChecker() { return mock(PermissionChecker.class); }
        @Bean ModelExecutionService execution() { return mock(ModelExecutionService.class); }
        @Bean ModelExecutionController controller(ModelExecutionService execution) { return new ModelExecutionController(execution); }
    }
}
