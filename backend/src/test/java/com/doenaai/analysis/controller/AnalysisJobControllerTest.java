package com.doenaai.analysis.controller;

import com.doenaai.analysis.dto.AnalysisJobCreateRequest;
import com.doenaai.analysis.service.AnalysisHistoryService;
import com.doenaai.analysis.service.AnalysisJobService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnalysisJobControllerTest {
    private final AnalysisJobService service = mock(AnalysisJobService.class);
    private final AnalysisJobController controller = new AnalysisJobController(service, mock(AnalysisHistoryService.class));
    private final UUID userId = UUID.randomUUID();
    private final Jwt user = Jwt.withTokenValue("test").header("alg", "none").subject(userId.toString()).build();

    @Test
    void missingBodyAndMissingOrNullThinkingChoiceDefaultToEnabled() {
        var mapper = JsonMapper.builder().build();
        for (String json : new String[]{"{}", "{\"thinkingEnabled\":null}", "{\"thinkingEnabled\":true}"}) {
            UUID projectId = UUID.randomUUID();
            var request = mapper.readValue(json, AnalysisJobCreateRequest.class);
            assertThat(request.thinkingEnabled()).isTrue();
            assertThat(request.thinkingEffort()).isEqualTo("medium");

            controller.createProjectScan(projectId, request, user);

            verify(service).createProjectScan(projectId, null, userId, null, false, true, "medium");
        }
        UUID projectId = UUID.randomUUID();
        controller.createProjectScan(projectId, null, user);
        verify(service).createProjectScan(projectId, null, userId, null, false, true, "medium");
    }

    @Test
    void explicitFalseIsKeptWhenCreatingAnalysis() {
        UUID projectId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        var request = JsonMapper.builder().build().readValue(
                "{\"requirementVersionId\":\"" + versionId + "\",\"thinkingEnabled\":false}",
                AnalysisJobCreateRequest.class);

        controller.createProjectScan(projectId, request, user);

        verify(service).createProjectScan(projectId, versionId, userId, null, false, false, "medium");
    }

    @Test
    void explicitEffortIsKeptEvenWhenThinkingIsDisabled() {
        var mapper = JsonMapper.builder().build();
        for (String effort : new String[]{"low", "medium", "high"}) {
            UUID projectId = UUID.randomUUID();
            var request = mapper.readValue("{\"thinkingEnabled\":false,\"thinkingEffort\":\"" + effort + "\"}",
                    AnalysisJobCreateRequest.class);

            controller.createProjectScan(projectId, request, user);

            verify(service).createProjectScan(projectId, null, userId, null, false, false, effort);
        }
        assertThat(mapper.readValue("{\"thinkingEffort\":null}", AnalysisJobCreateRequest.class).thinkingEffort())
                .isEqualTo("medium");
    }

    @Test
    void invalidEffortReturnsBadRequestBeforeCreatingAnyJob() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        for (String effort : new String[]{"", "MEDIUM", "maximum", " medium "}) {
            mvc.perform(post("/api/projects/{projectId}/analysis-jobs", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"thinkingEffort\":\"" + effort + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
}
