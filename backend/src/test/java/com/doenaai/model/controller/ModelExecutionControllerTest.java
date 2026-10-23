package com.doenaai.model.controller;

import com.doenaai.model.service.ModelCatalogException;
import com.doenaai.model.service.ModelExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 이력 조회의 기본 페이지와 검색 매개변수, 캐시 정책 및 안전한 오류 응답을 검사한다. */
class ModelExecutionControllerTest {
    private final ModelExecutionService service = mock(ModelExecutionService.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ModelExecutionController(service))
                .setControllerAdvice(new ModelExceptionHandler()).build();
    }

    @Test void missingParametersUseTheFirstTenRecordsAndNoStoreCachePolicy() throws Exception {
        when(service.operations(0, 10, "", "")).thenReturn(Map.of("operations", List.of(), "page", 0,
                "pageSize", 10, "totalCount", 0L, "totalPages", 0L));
        mvc.perform(get("/api/models/operations"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.operations").isEmpty()).andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.pageSize").value(10)).andExpect(jsonPath("$.totalCount").value(0));
        verify(service).operations(0, 10, "", "");
        verifyNoMoreInteractions(service);
    }

    @Test void forwardsOnlyExplicitPagingSearchAndStatusParameters() throws Exception {
        when(service.operations(3, 25, "qwen 학습", "SUCCEEDED")).thenReturn(Map.of("operations", List.of(), "page", 3));
        mvc.perform(get("/api/models/operations").param("page", "3").param("size", "25")
                        .param("query", "qwen 학습").param("status", "SUCCEEDED"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(service).operations(3, 25, "qwen 학습", "SUCCEEDED");
    }

    @Test void malformedNumericParametersAreRejectedBeforeTheService() throws Exception {
        for (String name : List.of("page", "size"))
            mvc.perform(get("/api/models/operations").param(name, "2147483648"))
                    .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(service);
    }

    @Test void serviceValidationAndStorageFailuresDoNotExposePrivateDetails() throws Exception {
        when(service.operations(-1, 10, "", ""))
                .thenThrow(new ModelCatalogException(HttpStatus.BAD_REQUEST, "이력 조회 조건을 확인해 주세요."));
        mvc.perform(get("/api/models/operations").param("page", "-1"))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
        when(service.operations(0, 10, "", "")).thenThrow(new DataAccessResourceFailureException("SQL password=private-value"));
        mvc.perform(get("/api/models/operations")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(not(containsString("private-value"))));
        verify(service, never()).execute(anyString(), any(), any(), any());
    }
}
