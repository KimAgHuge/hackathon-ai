package com.doenaai.model.controller;

import com.doenaai.auth.security.PermissionChecker;
import com.doenaai.model.dto.AdapterPrepareRequest;
import com.doenaai.model.service.AdapterManagementService;
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

/** 준비 API도 운영 배포 권한이 있는 사용자만 호출할 수 있다. */
class AdapterManagementControllerSecurityTest {
    private AnnotationConfigApplicationContext context;
    private AdapterManagementController controller;
    private AdapterManagementService service;
    private PermissionChecker permissions;
    private final UUID id = UUID.randomUUID();

    @BeforeEach void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        controller = context.getBean(AdapterManagementController.class);
        service = context.getBean(AdapterManagementService.class);
        permissions = context.getBean(PermissionChecker.class);
        var jwt = Jwt.withTokenValue("synthetic-token").header("alg", "none").subject(UUID.randomUUID().toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        when(service.prepare(id)).thenReturn(Map.of("status", "WAITING"));
    }

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); context.close(); }

    @Test void readAndTrainingPermissionsDoNotAllowAdapterPreparation() {
        when(permissions.has(any(), eq("MODEL_READ"))).thenReturn(true);
        when(permissions.has(any(), eq("MODEL_TRAIN"))).thenReturn(true);
        assertThatThrownBy(() -> controller.prepare(id, null)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(service);
    }

    @Test void deployPermissionAllowsPreparationAndDisablesResponseCaching() {
        when(permissions.has(any(), eq("MODEL_DEPLOY"))).thenReturn(true);
        var response = controller.prepare(id, new AdapterPrepareRequest(UUID.randomUUID()));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        verify(service).prepare(id);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Config {
        @Bean PermissionChecker permissionChecker() { return mock(PermissionChecker.class); }
        @Bean AdapterManagementService service() { return mock(AdapterManagementService.class); }
        @Bean AdapterManagementController controller(AdapterManagementService service) { return new AdapterManagementController(service); }
    }
}
