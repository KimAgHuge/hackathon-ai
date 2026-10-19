package com.doenaai.analysis.controller;

import com.doenaai.analysis.dto.TrainingCaseDraftResponse;
import com.doenaai.analysis.dto.TrainingTransferRequest;
import com.doenaai.analysis.service.AnalysisResultService;
import com.doenaai.analysis.service.TrainingTransferClient;
import com.doenaai.analysis.service.TrainingEvidenceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AnalysisTrainingTransferControllerTest {
    @Test
    @SuppressWarnings("unchecked")
    void fillsServerOwnedIdentityAndApprovalTimeWithoutChangingReviewContent() {
        var results = mock(AnalysisResultService.class);
        var client = mock(TrainingTransferClient.class);
        var evidence = mock(TrainingEvidenceService.class);
        when(evidence.verifiedCase(any(), any(), anyMap())).thenAnswer(call -> call.getArgument(2));
        var controller = new AnalysisResultController(results, client, evidence);
        var jobId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var expected = Map.<String, Object>of(
                "caseId", "analysis-" + jobId,
                "projectGroup", "project-stable",
                "approval", Map.of("reviewerId", "reviewer-stable", "approvedAt", "2026-10-19T00:00:00Z"),
                "request", Map.of("projectId", "project-a"),
                "snapshot", Map.of("requirementVersionId", "version-a"));
        when(results.trainingDraft(jobId, userId)).thenReturn(new TrainingCaseDraftResponse(expected, List.of()));
        when(client.publish(eq(jobId), anyMap())).thenReturn(Map.of("status", "WAITING_FOR_SYNC"));
        var submitted = Map.<String, Object>of(
                "caseId", "analysis-" + jobId,
                "projectGroup", "client-edited-group",
                "humanApproved", true,
                "sanitized", true,
                "approval", Map.of("reviewerId", "client-edited-reviewer", "approvedAt", "client-edited-time", "note", "근거 확인 완료"),
                "request", Map.of("projectId", "project-a"),
                "snapshot", Map.of("requirementVersionId", "version-a"));
        var jwt = Jwt.withTokenValue("synthetic-token").header("alg", "none").subject(userId.toString()).build();

        controller.publishTrainingCase(jobId, new TrainingTransferRequest(submitted, true), jwt);

        var captured = ArgumentCaptor.forClass(Map.class);
        verify(client).publish(eq(jobId), captured.capture());
        assertThat(captured.getValue().get("projectGroup")).isEqualTo("project-stable");
        assertThat((Map<String, Object>) captured.getValue().get("approval"))
                .containsEntry("reviewerId", "reviewer-stable")
                .containsEntry("approvedAt", "2026-10-19T00:00:00Z")
                .containsEntry("note", "근거 확인 완료");
        assertThat(submitted.get("projectGroup")).isEqualTo("client-edited-group");
    }

    @Test
    void rejectsCaseFromAnotherProjectOrRequirement() {
        var results = mock(AnalysisResultService.class);
        var client = mock(TrainingTransferClient.class);
        var controller = new AnalysisResultController(results, client, mock(TrainingEvidenceService.class));
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
        var controller = new AnalysisResultController(results, client, mock(TrainingEvidenceService.class));
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
