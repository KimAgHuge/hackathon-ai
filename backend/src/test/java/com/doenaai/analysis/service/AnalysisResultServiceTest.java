package com.doenaai.analysis.service;

import com.doenaai.analysis.dto.AnalysisJobResponse;
import com.doenaai.analysis.dto.AnalysisResultResponse;
import com.doenaai.analysis.dto.AnalysisReviewDecision;
import com.doenaai.analysis.dto.AnalysisReviewRequest;
import com.doenaai.analysis.repository.AnalysisResultRepository;
import com.doenaai.project.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisResultServiceTest {
    @Test void trainingDraftRequiresProjectManagementAndApproval() {
        var jobs = mock(AnalysisJobService.class);
        var results = mock(AnalysisResultRepository.class);
        var projects = mock(ProjectService.class);
        var jobId = UUID.randomUUID();
        var projectId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        when(jobs.getAnalysisJob(jobId, userId)).thenReturn(AnalysisJobResponse.of(jobId, projectId, versionId,
                "COMPLETED", 100, 100, 100, OffsetDateTime.now()));
        doThrow(new org.springframework.security.access.AccessDeniedException("거부"))
                .when(projects).assertCanManage(projectId, userId);
        var service = new AnalysisResultService(jobs, results, projects, mock(AnalysisReviewValidator.class));

        assertThatThrownBy(() -> service.trainingDraft(jobId, userId))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(results);

        doNothing().when(projects).assertCanManage(projectId, userId);
        when(results.findByJobId(jobId)).thenReturn(Optional.of(new AnalysisResultResponse(
                jobId, projectId, "요약", "LOW", 10, Map.of("summary", "요약"),
                Map.of(), OffsetDateTime.now(), "PENDING", null, null, null, null)));
        assertThatThrownBy(() -> service.trainingDraft(jobId, userId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test void rejectsUnauthorizedAccessBeforeLookingUpResult() {
        var jobs = mock(AnalysisJobService.class);
        var results = mock(AnalysisResultRepository.class);
        var id = UUID.randomUUID();
        var user = UUID.randomUUID();
        when(jobs.getAnalysisJob(id, user)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> new AnalysisResultService(jobs, results,
                mock(ProjectService.class), mock(AnalysisReviewValidator.class)).get(id, user))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(results);
    }

    @Test void pendingJobReturnsConflictNotAFakeResult() {
        var jobs = mock(AnalysisJobService.class);
        var results = mock(AnalysisResultRepository.class);
        var id = UUID.randomUUID();
        var user = UUID.randomUUID();
        when(jobs.getAnalysisJob(id, user)).thenReturn(AnalysisJobResponse.of(id, UUID.randomUUID(), null,
                "PENDING", 0, 0, 0, OffsetDateTime.now()));
        assertThatThrownBy(() -> new AnalysisResultService(jobs, results,
                mock(ProjectService.class), mock(AnalysisReviewValidator.class)).get(id, user))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verifyNoInteractions(results);
    }

    @Test void reviewRequiresProjectManageAccessBeforeWriting() {
        var jobs = mock(AnalysisJobService.class);
        var results = mock(AnalysisResultRepository.class);
        var projects = mock(ProjectService.class);
        var id = UUID.randomUUID();
        var projectId = UUID.randomUUID();
        var user = UUID.randomUUID();
        when(jobs.getAnalysisJob(id, user)).thenReturn(AnalysisJobResponse.of(id, projectId, null,
                "COMPLETED", 100, 100, 100, OffsetDateTime.now()));
        doThrow(new org.springframework.security.access.AccessDeniedException("거부"))
                .when(projects).assertCanManage(projectId, user);

        assertThatThrownBy(() -> new AnalysisResultService(jobs, results, projects,
                mock(AnalysisReviewValidator.class)).review(id, user,
                new AnalysisReviewRequest(AnalysisReviewDecision.APPROVED, "검토 완료", null)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(results);
    }

    @Test void approvedCorrectionIsValidatedAndSavedOnce() {
        var jobs = mock(AnalysisJobService.class);
        var results = mock(AnalysisResultRepository.class);
        var projects = mock(ProjectService.class);
        var validator = mock(AnalysisReviewValidator.class);
        var id = UUID.randomUUID();
        var projectId = UUID.randomUUID();
        var user = UUID.randomUUID();
        var corrected = Map.<String, Object>of("summary", "사람이 수정한 결론");
        var saved = new AnalysisResultResponse(id, projectId, "사람이 수정한 결론", "LOW", 20,
                Map.of("summary", "AI 초안"), Map.of(), OffsetDateTime.now(), "APPROVED",
                corrected, "근거 확인", user, OffsetDateTime.now());
        when(jobs.getAnalysisJob(id, user)).thenReturn(AnalysisJobResponse.of(id, projectId, null,
                "COMPLETED", 100, 100, 100, OffsetDateTime.now()));
        when(results.review(id, user, AnalysisReviewDecision.APPROVED, "근거 확인", corrected)).thenReturn(true);
        when(results.findByJobId(id)).thenReturn(Optional.of(saved));

        var response = new AnalysisResultService(jobs, results, projects, validator).review(id, user,
                new AnalysisReviewRequest(AnalysisReviewDecision.APPROVED, " 근거 확인 ", corrected));

        assertThat(response.reviewStatus()).isEqualTo("APPROVED");
        verify(validator).validate(corrected);
        verify(projects).assertCanManage(projectId, user);
        verify(results).review(id, user, AnalysisReviewDecision.APPROVED, "근거 확인", corrected);
    }

    @Test void inconsistentOriginalRiskCannotBeApproved() {
        var jobs = mock(AnalysisJobService.class);
        var results = mock(AnalysisResultRepository.class);
        var projectId = UUID.randomUUID();
        var jobId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(jobs.getAnalysisJob(jobId, userId)).thenReturn(AnalysisJobResponse.of(jobId, projectId, null,
                "COMPLETED", 100, 100, 100, OffsetDateTime.now()));
        when(results.findByJobId(jobId)).thenReturn(Optional.of(new AnalysisResultResponse(
                jobId, projectId, "이전 분석", "HIGH", 8, Map.of("summary", "이전 분석"),
                Map.of(), OffsetDateTime.now(), "PENDING", null, null, null, null)));

        var service = new AnalysisResultService(jobs, results,
                mock(ProjectService.class), mock(AnalysisReviewValidator.class));

        assertThatThrownBy(() -> service.review(jobId, userId,
                new AnalysisReviewRequest(AnalysisReviewDecision.APPROVED, "근거 확인", null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(results, never()).review(any(), any(), any(), any(), any());
    }

    @Test void aStoredDuplicatePlanRemainsReadableButCannotBeNewlyApproved() {
        var jobs = mock(AnalysisJobService.class);
        var results = mock(AnalysisResultRepository.class);
        var jobId = UUID.randomUUID();
        var projectId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var column = Map.<String, Object>of("targetTable", "example.table_example", "targetColumn", "column_example", "changeType", "ADD_COLUMN");
        var original = new AnalysisResultResponse(jobId, projectId, "구형 분석 결과", "LOW", 10,
                Map.of("riskScore", 10, "databaseImpact", Map.of("status", "CHANGE_REQUIRED", "proposedChanges", java.util.List.of(column, column))),
                Map.of(), OffsetDateTime.now(), "PENDING", null, null, null, null);
        when(jobs.getAnalysisJob(jobId, userId)).thenReturn(AnalysisJobResponse.of(jobId, projectId, null,
                "COMPLETED", 100, 100, 100, OffsetDateTime.now()));
        when(results.findByJobId(jobId)).thenReturn(Optional.of(original));
        var service = new AnalysisResultService(jobs, results, mock(ProjectService.class), mock(AnalysisReviewValidator.class));

        assertThat(service.get(jobId, userId)).isSameAs(original);
        assertThatThrownBy(() -> service.review(jobId, userId,
                new AnalysisReviewRequest(AnalysisReviewDecision.APPROVED, "검토 후 승인", null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(results, never()).review(any(), any(), any(), any(), any());
    }

    @Test void pendingAndAlreadyReviewedJobsCannotBeReviewed() {
        var jobs = mock(AnalysisJobService.class);
        var results = mock(AnalysisResultRepository.class);
        var projects = mock(ProjectService.class);
        var validator = mock(AnalysisReviewValidator.class);
        var id = UUID.randomUUID();
        var projectId = UUID.randomUUID();
        var user = UUID.randomUUID();
        var request = new AnalysisReviewRequest(AnalysisReviewDecision.REJECTED, "잘못된 근거", null);
        when(jobs.getAnalysisJob(id, user)).thenReturn(AnalysisJobResponse.of(id, projectId, null,
                "RUNNING", 40, 0, 0, OffsetDateTime.now()));
        var service = new AnalysisResultService(jobs, results, projects, validator);

        assertThatThrownBy(() -> service.review(id, user, request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verifyNoInteractions(results);

        when(jobs.getAnalysisJob(id, user)).thenReturn(AnalysisJobResponse.of(id, projectId, null,
                "COMPLETED", 100, 100, 100, OffsetDateTime.now()));
        assertThatThrownBy(() -> service.review(id, user, request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(results).review(id, user, AnalysisReviewDecision.REJECTED, "잘못된 근거", null);
    }
}
