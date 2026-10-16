package com.doenaai.analysis.controller;

import com.doenaai.analysis.dto.TrainingCaseDraftResponse;
import com.doenaai.analysis.dto.TrainingTransferRequest;
import com.doenaai.analysis.service.AnalysisResultService;
import com.doenaai.analysis.service.TrainingTransferClient;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AnalysisTrainingTransferControllerTest {
    @Test
    void rejectsCaseFromAnotherProjectOrRequirement() {
        var results = mock(AnalysisResultService.class);
        var client = mock(TrainingTransferClient.class);
        var controller = new AnalysisResultController(results, client);
        var jobId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var expected = Map.<String, Object>of(
                "caseId", "analysis-" + jobId,
                "request", Map.of("projectId", "project-a"),
                "snapshot", Map.of("requirementVersionId", "version-a"));
        when(results.trainingDraft(jobId, userId)).thenReturn(new TrainingCaseDraftResponse(expected, List.of()));
        var jwt = Jwt.withTokenValue("synthetic-token").header("alg", "none").subject(userId.toString()).build();
        var foreign = Map.<String, Object>of(
                "caseId", "analysis-" + jobId,
                "humanApproved", true,
                "sanitized", true,
                "request", Map.of("projectId", "project-b"),
                "snapshot", Map.of("requirementVersionId", "version-a"));

        assertThatThrownBy(() -> controller.publishTrainingCase(jobId,
                new TrainingTransferRequest(foreign, true), jwt)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(client);
    }

    @Test
    void rejectsUnconfirmedReviewEvenWhenFlagsAreTrue() {
        var results = mock(AnalysisResultService.class);
        var client = mock(TrainingTransferClient.class);
        var controller = new AnalysisResultController(results, client);
        var jobId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var record = Map.<String, Object>of(
                "caseId", "analysis-" + jobId,
                "humanApproved", true,
                "sanitized", true,
                "request", Map.of("projectId", "project-a"),
                "snapshot", Map.of("requirementVersionId", "version-a"));
        when(results.trainingDraft(jobId, userId)).thenReturn(new TrainingCaseDraftResponse(record, List.of()));
        var jwt = Jwt.withTokenValue("synthetic-token").header("alg", "none").subject(userId.toString()).build();

        assertThatThrownBy(() -> controller.publishTrainingCase(jobId,
                new TrainingTransferRequest(record, false), jwt)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(client);
    }
}
