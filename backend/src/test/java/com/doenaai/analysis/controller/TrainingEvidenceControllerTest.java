package com.doenaai.analysis.controller;

import com.doenaai.analysis.dto.TrainingCaseDraftResponse;
import com.doenaai.analysis.service.AnalysisResultService;
import com.doenaai.analysis.service.TrainingEvidenceService;
import com.doenaai.analysis.service.TrainingTransferClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.bind.support.WebDataBinderFactory;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 JSON 바인딩을 거쳐 게시 동의 없는 보강·사전 검사 요청을 검증한다. */
class TrainingEvidenceControllerTest {
    private final UUID jobId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final TrainingEvidenceService evidence = mock(TrainingEvidenceService.class);
    private final AnalysisResultService results = mock(AnalysisResultService.class);
    private final TrainingTransferClient transfers = mock(TrainingTransferClient.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Map<String, Object> caseDraft = Map.of(
            "caseId", "analysis-" + jobId,
            "request", Map.of("projectId", "project-a"),
            "snapshot", Map.of("requirementVersionId", "version-a", "sourceRevision", "a".repeat(40)));
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        var jwt = Jwt.withTokenValue("synthetic-token").header("alg", "none").subject(userId.toString()).build();
        mvc = MockMvcBuilders.standaloneSetup(new TrainingEvidenceController(evidence, results, transfers))
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
    void supplementsJsonWithoutPublishConfirmation() throws Exception {
        when(evidence.supplement(jobId, userId, caseDraft))
                .thenReturn(new TrainingCaseDraftResponse(caseDraft, List.of()));

        mvc.perform(post("/api/analysis-jobs/{jobId}/training-case-evidence", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("caseDraft", caseDraft))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.caseDraft.caseId").value("analysis-" + jobId));

        verify(evidence).supplement(jobId, userId, caseDraft);
        verifyNoInteractions(transfers);
    }

    @Test
    void validatesJsonWithoutPublishConfirmation() throws Exception {
        when(results.trainingDraft(jobId, userId)).thenReturn(new TrainingCaseDraftResponse(caseDraft, List.of()));
        when(transfers.validateCase(jobId, caseDraft)).thenReturn(Map.of("valid", true));

        mvc.perform(post("/api/analysis-jobs/{jobId}/training-case-validation", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("caseDraft", caseDraft))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));

        verify(transfers).validateCase(jobId, caseDraft);
        verify(transfers, never()).publish(any(), anyMap());
    }

    @Test
    void rejectsMissingCaseBeforeCallingServices() throws Exception {
        for (String endpoint : List.of("training-case-evidence", "training-case-validation")) {
            mvc.perform(post("/api/analysis-jobs/{jobId}/" + endpoint, jobId)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(evidence, results, transfers);
    }

    @Test
    void rejectsForeignCaseBeforeCallingInferenceServer() throws Exception {
        when(results.trainingDraft(jobId, userId)).thenReturn(new TrainingCaseDraftResponse(caseDraft, List.of()));
        mvc.perform(post("/api/analysis-jobs/{jobId}/training-case-validation", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("caseDraft", Map.of("caseId", "other")))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(transfers);
    }
}
