package com.doenaai.analysis.controller;

import com.doenaai.analysis.dto.AnalysisHistoryPage;
import com.doenaai.analysis.service.AnalysisHistoryService;
import com.doenaai.analysis.service.AnalysisJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 저장 자료 선택 필터가 기존 이력 조회의 프로젝트와 페이지 조건을 유지하는지 검사한다. */
class AnalysisSavedEvidenceControllerTest {
    private final UUID userId = UUID.randomUUID();
    private final AnalysisJobService jobs = mock(AnalysisJobService.class);
    private final AnalysisHistoryService history = mock(AnalysisHistoryService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        var jwt = Jwt.withTokenValue("synthetic-token").header("alg", "none").subject(userId.toString()).build();
        mvc = MockMvcBuilders.standaloneSetup(new AnalysisJobController(jobs, history))
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType() == Jwt.class;
                    }

                    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                            NativeWebRequest request, WebDataBinderFactory binder) {
                        return jwt;
                    }
                }).build();
    }

    @Test
    void savedEvidenceFilterKeepsProjectAndPageParameters() throws Exception {
        UUID projectId = UUID.randomUUID();
        when(history.list(userId, projectId, 2, 20, true))
                .thenReturn(new AnalysisHistoryPage(List.of(), 47, 2, 20));

        mvc.perform(get("/api/analysis-jobs")
                        .param("projectId", projectId.toString())
                        .param("page", "2").param("size", "20")
                        .param("reusableEvidenceOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(47))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(20));

        verify(history).list(userId, projectId, 2, 20, true);
        verifyNoInteractions(jobs);
    }

    @Test
    void omittedFilterKeepsTheDefaultUnfilteredHistory() throws Exception {
        when(history.list(userId, null, 0, 10))
                .thenReturn(new AnalysisHistoryPage(List.of(), 0, 0, 10));

        mvc.perform(get("/api/analysis-jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10));

        verify(history).list(userId, null, 0, 10);
        verifyNoInteractions(jobs);
    }

    @Test
    void explicitFalseDoesNotRemoveTheProjectFilter() throws Exception {
        UUID projectId = UUID.randomUUID();
        when(history.list(userId, projectId, 0, 10))
                .thenReturn(new AnalysisHistoryPage(List.of(), 0, 0, 10));

        mvc.perform(get("/api/analysis-jobs")
                        .param("projectId", projectId.toString())
                        .param("reusableEvidenceOnly", "false"))
                .andExpect(status().isOk());

        verify(history).list(userId, projectId, 0, 10);
        verifyNoInteractions(jobs);
    }

    @Test
    void invalidPageAndSizeStopBeforeReadingSavedEvidence() throws Exception {
        for (String[] range : new String[][]{
                {"-1", "10"}, {"0", "0"}, {"0", "-1"}, {"0", "101"},
                {Integer.toString(Integer.MAX_VALUE), "2"}, {"invalid", "10"}, {"0", "invalid"},
        }) {
            mvc.perform(get("/api/analysis-jobs")
                            .param("page", range[0]).param("size", range[1])
                            .param("reusableEvidenceOnly", "true"))
                    .andExpect(status().isBadRequest());
        }

        verifyNoInteractions(history, jobs);
    }

    @Test
    void invalidProjectAndFilterValuesStopBeforeReadingHistory() throws Exception {
        mvc.perform(get("/api/analysis-jobs")
                        .param("projectId", "invalid-project")
                        .param("reusableEvidenceOnly", "true"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/analysis-jobs").param("reusableEvidenceOnly", "invalid"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(history, jobs);
    }
}
