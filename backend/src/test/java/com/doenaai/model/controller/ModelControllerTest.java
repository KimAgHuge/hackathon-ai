package com.doenaai.model.controller;

import com.doenaai.model.dto.ModelVersionCreateRequest;
import com.doenaai.model.service.ModelCatalogException;
import com.doenaai.model.service.ModelCatalogService;
import com.doenaai.model.service.ModelHistorySyncService;
import com.doenaai.model.service.ModelRuntimeClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.bind.support.WebDataBinderFactory;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ModelControllerTest {
    private final UUID actor = UUID.randomUUID();
    private final ModelCatalogService catalog = mock(ModelCatalogService.class);
    private final ModelRuntimeClient runtime = mock(ModelRuntimeClient.class);
    private final ModelHistorySyncService history = mock(ModelHistorySyncService.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private MockMvc mvc;

    @BeforeEach void setup() {
        var jwt = Jwt.withTokenValue("synthetic-token").header("alg", "none").subject(actor.toString()).build();
        mvc = MockMvcBuilders.standaloneSetup(new ModelController(catalog, runtime, history))
                .setControllerAdvice(new ModelExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(MethodParameter parameter) { return parameter.getParameterType() == Jwt.class; }
                    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                            NativeWebRequest request, WebDataBinderFactory binder) { return jwt; }
                }).build();
    }

    @Test void servesCatalogAndRuntimeWithNoStoreCachePolicy() throws Exception {
        when(catalog.catalog()).thenReturn(Map.of("limit", 100));
        when(runtime.status()).thenReturn(Map.of("available", true));
        mvc.perform(get("/api/models")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.limit").value(100));
        mvc.perform(get("/api/models/runtime")).andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test void createsVersionWithJwtActorAndReturnsDetail() throws Exception {
        UUID id = UUID.randomUUID();
        when(catalog.createVersion(any(), eq(actor))).thenReturn(Map.of("id", id, "readiness", Map.of("canDeploy", false)));
        var request = Map.of("modelName", "분석 모델", "baseModelRef", "example/model", "version", "v1", "artifactType", "LORA");
        mvc.perform(post("/api/models/versions").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.readiness.canDeploy").value(false));
        verify(catalog).createVersion(argThat(value -> value.modelName().equals("분석 모델")), eq(actor));
    }

    @Test void synchronizesHistoryWithAnEmptyObjectAndNoStoreCachePolicy() throws Exception {
        when(history.synchronize()).thenReturn(Map.of("status", "SUCCEEDED", "registrationsChecked", 1, "deploymentsChecked", 1));
        mvc.perform(post("/api/models/history/sync").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.registrationsChecked").value(1))
                .andExpect(jsonPath("$.deploymentsChecked").value(1));
        verify(history).synchronize();
        verifyNoInteractions(catalog, runtime);
    }

    @Test void rejectsMissingNullOrNonemptySynchronizationBodiesBeforeCallingTheService() throws Exception {
        for (String body : new String[]{"", "null", "{\"model\":\"qwen3.8:27b\"}", "{\"path\":\"/private/model\"}"}) {
            mvc.perform(post("/api/models/history/sync").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(history);
    }

    @Test void storesManualEvaluationWithJwtActorAndReturnsDetail() throws Exception {
        UUID id = UUID.randomUUID();
        when(catalog.recordEvaluation(eq(id), any(), eq(actor))).thenReturn(Map.of("id", id, "status", "READY"));
        mvc.perform(post("/api/models/versions/{id}/evaluations", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PASSED\",\"notes\":\"수동 확인\",\"metrics\":{\"sampleCount\":3}}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("READY"));
        verify(catalog).recordEvaluation(eq(id), argThat(value -> value.status().equals("PASSED")), eq(actor));
    }

    @Test void returnsSafeErrorsForBadIdsBrokenJsonAndDatabaseFailures() throws Exception {
        mvc.perform(get("/api/models/versions/not-an-id")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/models/versions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":\"password=private-value\"}"))
                .andExpect(status().isBadRequest()).andExpect(content().string(not(containsString("private-value"))));
        when(catalog.catalog()).thenThrow(new DataAccessResourceFailureException("SQL password=private-value"));
        mvc.perform(get("/api/models")).andExpect(status().isServiceUnavailable())
                .andExpect(content().string(not(containsString("SQL"))))
                .andExpect(content().string(not(containsString("private-value"))));
    }

    @Test void usesFixedConflictAndMissingResponses() throws Exception {
        when(catalog.createVersion(any(ModelVersionCreateRequest.class), eq(actor)))
                .thenThrow(new ModelCatalogException(HttpStatus.CONFLICT, "같은 모델 버전이 이미 등록되어 있습니다."));
        mvc.perform(post("/api/models/versions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
        UUID id = UUID.randomUUID();
        when(catalog.version(id)).thenThrow(new ModelCatalogException(HttpStatus.NOT_FOUND, "모델 버전을 찾을 수 없습니다."));
        mvc.perform(get("/api/models/versions/{id}", id)).andExpect(status().isNotFound());
    }
}
