package com.doenaai.model.controller;

import com.doenaai.auth.security.PermissionChecker;
import com.doenaai.model.service.TrainedModelManagementService;
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

/** 조회·학습·배포 권한을 분리하고 등록 API가 기존 권한 경계를 유지하는지 검사한다. */
class TrainedModelManagementControllerSecurityTest {
    private AnnotationConfigApplicationContext context;
    private TrainedModelManagementController controller;
    private TrainedModelManagementService service;
    private PermissionChecker permissions;
    private final UUID id = UUID.randomUUID();

    @BeforeEach void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        controller = context.getBean(TrainedModelManagementController.class);
        service = context.getBean(TrainedModelManagementService.class);
        permissions = context.getBean(PermissionChecker.class);
        var jwt = Jwt.withTokenValue("synthetic-token").header("alg", "none").subject(UUID.randomUUID().toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); context.close(); }

    @Test void readPermissionCanPollBothServersButCannotBuildCancelOrRegister() {
        when(permissions.has(any(), eq("MODEL_READ"))).thenReturn(true);
        when(service.build(id, "GET", Map.of())).thenReturn(Map.of("status", "NOT_BUILT"));
        when(service.importStatus(id)).thenReturn(Map.of("status", "NOT_STARTED"));
        assertThat(controller.buildStatus(id).getHeaders().getCacheControl()).contains("no-store");
        assertThat(controller.importStatus(id).getHeaders().getCacheControl()).contains("no-store");
        assertThatThrownBy(() -> controller.build(id, Map.of())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.cancel(id, Map.of())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.register(id, Map.of())).isInstanceOf(AccessDeniedException.class);
        verify(service, never()).build(id, "POST", Map.of());
        verify(service, never()).register(any(), any());
    }

    @Test void trainingPermissionCanBuildAndCancelButCannotRegister() {
        when(permissions.has(any(), eq("MODEL_TRAIN"))).thenReturn(true);
        when(service.build(id, "POST", Map.of())).thenReturn(Map.of("status", "PENDING"));
        when(service.cancel(id, Map.of())).thenReturn(Map.of("status", "CANCELLED"));
        assertThat(controller.build(id, Map.of()).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.cancel(id, Map.of()).getHeaders().getCacheControl()).contains("no-store");
        assertThatThrownBy(() -> controller.register(id, Map.of())).isInstanceOf(AccessDeniedException.class);
        verify(service, never()).register(any(), any());
    }

    @Test void deployPermissionCanRegisterButDoesNotGrantTraining() {
        when(permissions.has(any(), eq("MODEL_DEPLOY"))).thenReturn(true);
        when(service.register(id, Map.of())).thenReturn(Map.of("status", "IMPORTING"));
        var response = controller.register(id, Map.of());
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThatThrownBy(() -> controller.build(id, Map.of())).isInstanceOf(AccessDeniedException.class);
        verify(service).register(id, Map.of());
    }

    @Test void missingPermissionsRejectAllOperationsBeforeTheService() {
        assertThatThrownBy(() -> controller.buildStatus(id)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.importStatus(id)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.build(id, Map.of())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.cancel(id, Map.of())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.register(id, Map.of())).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(service);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Config {
        @Bean PermissionChecker permissionChecker() { return mock(PermissionChecker.class); }
        @Bean TrainedModelManagementService service() { return mock(TrainedModelManagementService.class); }
        @Bean TrainedModelManagementController controller(TrainedModelManagementService service) { return new TrainedModelManagementController(service); }
    }
}
