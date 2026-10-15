package com.doenaai.requirement.service;

import com.doenaai.analysis.dto.AnalysisJobResponse;
import com.doenaai.analysis.dto.AnalysisResultResponse;
import com.doenaai.analysis.repository.AnalysisJobRepository;
import com.doenaai.analysis.repository.AnalysisResultRepository;
import com.doenaai.project.dto.ProjectAccessContext;
import com.doenaai.project.service.ProjectService;
import com.doenaai.requirement.dto.RequirementFollowUpRequest;
import com.doenaai.requirement.entity.Requirement;
import com.doenaai.requirement.entity.RequirementPriority;
import com.doenaai.requirement.entity.RequirementSourceType;
import com.doenaai.requirement.entity.RequirementVersion;
import com.doenaai.requirement.repository.RequirementRepository;
import com.doenaai.requirement.repository.RequirementVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequirementFollowUpServiceTest {
    @Mock RequirementRepository requirements;
    @Mock RequirementVersionRepository versions;
    @Mock ProjectService projects;
    @Mock AnalysisJobRepository jobs;
    @Mock AnalysisResultRepository results;

    RequirementFollowUpService service;
    UUID requirementId = UUID.randomUUID();
    UUID projectId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RequirementFollowUpService(requirements, versions, projects, jobs, results);
        var requirement = new Requirement(organizationId, projectId, "REQ-1", "멤버 변경",
                RequirementSourceType.PM, null, RequirementPriority.MEDIUM, false, userId);
        when(requirements.findById(requirementId)).thenReturn(Optional.of(requirement));
        when(projects.getAccessContext(projectId, userId))
                .thenReturn(new ProjectAccessContext(projectId, organizationId, "PM"));
    }

    @Test
    void preservesRequirementAndLinksAnswerToOriginalAnalysisQuestion() {
        var prior = Map.<String, Object>of("kind", "AI_QUESTION_ANSWER", "question", "이전 질문", "answer", "이전 답변");
        var current = version(versionId, 1, List.of(prior), null);
        var saved = version(UUID.randomUUID(), 2, List.of(), jobId);
        when(versions.findCurrent(requirementId)).thenReturn(Optional.of(current));
        when(jobs.findById(jobId)).thenReturn(Optional.of(
                AnalysisJobResponse.of(jobId, projectId, versionId, "COMPLETED", 100, 100, 100, OffsetDateTime.now())));
        when(results.findByJobId(jobId)).thenReturn(Optional.of(new AnalysisResultResponse(
                jobId, projectId, "요약", "UNKNOWN", 0,
                Map.of("databaseImpact", Map.of("openQuestions", List.of("어느 멤버 테이블인가요?"))),
                Map.of(), OffsetDateTime.now())));
        when(versions.findNextVersionNumber(requirementId)).thenReturn(2);
        when(versions.insert(eq(requirementId), eq(2), any(), eq(userId), anyList(), eq(jobId), eq(versionId)))
                .thenReturn(saved);

        service.create(requirementId, new RequirementFollowUpRequest(jobId, versionId,
                List.of(new RequirementFollowUpRequest.Answer(0, "organization_members입니다.")),
                "다른 조직에 속한 경우는 어떻게 되나요?"), userId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object>> clarifications = ArgumentCaptor.forClass(List.class);
        verify(versions).insert(eq(requirementId), eq(2), any(), eq(userId), clarifications.capture(), eq(jobId), eq(versionId));
        assertThat(clarifications.getValue()).hasSize(3);
        assertThat(clarifications.getValue().get(0)).isEqualTo(prior);
        @SuppressWarnings("unchecked")
        Map<String, Object> answerEntry = (Map<String, Object>) clarifications.getValue().get(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> questionEntry = (Map<String, Object>) clarifications.getValue().get(2);
        assertThat(answerEntry).containsEntry("questionIndex", 0)
                .containsEntry("questionKey", jobId + ":0")
                .containsEntry("sourceAnalysisJobId", jobId.toString())
                .containsEntry("question", "어느 멤버 테이블인가요?")
                .containsEntry("answer", "organization_members입니다.");
        assertThat(questionEntry)
                .containsEntry("kind", "USER_QUESTION")
                .containsEntry("question", "다른 조직에 속한 경우는 어떻게 되나요?");
        verify(versions).markCurrentVersionHistorical(requirementId);
    }

    @Test
    void rejectsStaleVersionBeforeWriting() {
        when(versions.findCurrent(requirementId)).thenReturn(Optional.of(version(UUID.randomUUID(), 2, List.of(), null)));
        when(jobs.findById(jobId)).thenReturn(Optional.of(completedJob()));

        assertThatThrownBy(() -> service.create(requirementId,
                new RequirementFollowUpRequest(jobId, versionId, List.of(), "추가 질문"), userId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(versions, never()).markCurrentVersionHistorical(any());
    }

    @Test
    void retriesCancelledFollowUpWithoutDuplicatingTheSameAnswers() {
        UUID retryVersionId = UUID.randomUUID();
        var savedAnswer = Map.<String, Object>of(
                "kind", "AI_QUESTION_ANSWER", "questionKey", jobId + ":0",
                "question", "저장 위치는?", "answer", "public.profiles");
        var current = version(retryVersionId, 2, List.of(savedAnswer), jobId);
        when(versions.findCurrent(requirementId)).thenReturn(Optional.of(current));
        when(jobs.findById(jobId)).thenReturn(Optional.of(completedJob()));
        when(results.findByJobId(jobId)).thenReturn(Optional.of(resultWithQuestion("저장 위치는?")));

        var response = service.create(requirementId, new RequirementFollowUpRequest(jobId, versionId,
                List.of(new RequirementFollowUpRequest.Answer(0, "public.profiles")), null), userId);

        assertThat(response.id()).isEqualTo(retryVersionId);
        verify(jobs).hasActiveOrCompletedAnalysis(retryVersionId);
        verify(versions, never()).markCurrentVersionHistorical(any());
    }

    @Test
    void updatesAnswerAfterFailedFollowUpAndKeepsVersionHistory() {
        UUID failedVersionId = UUID.randomUUID();
        var oldAnswer = Map.<String, Object>of(
                "kind", "AI_QUESTION_ANSWER", "questionKey", jobId + ":0",
                "question", "저장 위치는?", "answer", "auth.users");
        when(versions.findCurrent(requirementId)).thenReturn(Optional.of(version(failedVersionId, 2, List.of(oldAnswer), jobId)));
        when(jobs.findById(jobId)).thenReturn(Optional.of(completedJob()));
        when(results.findByJobId(jobId)).thenReturn(Optional.of(resultWithQuestion("저장 위치는?")));
        when(versions.findNextVersionNumber(requirementId)).thenReturn(3);
        when(versions.insert(eq(requirementId), eq(3), any(), eq(userId), anyList(), eq(jobId), eq(failedVersionId)))
                .thenReturn(version(UUID.randomUUID(), 3, List.of(), jobId));

        service.create(requirementId, new RequirementFollowUpRequest(jobId, versionId,
                List.of(new RequirementFollowUpRequest.Answer(0, "public.profiles")), null), userId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object>> saved = ArgumentCaptor.forClass(List.class);
        verify(versions).insert(eq(requirementId), eq(3), any(), eq(userId), saved.capture(), eq(jobId), eq(failedVersionId));
        assertThat(saved.getValue()).hasSize(1);
        assertThat(((Map<?, ?>) saved.getValue().getFirst()).get("answer")).isEqualTo("public.profiles");
        verify(versions).markCurrentVersionHistorical(requirementId);
    }

    @Test
    void doesNotContinueOldQuestionsWhileLatestAnalysisIsRunning() {
        UUID retryVersionId = UUID.randomUUID();
        when(versions.findCurrent(requirementId)).thenReturn(Optional.of(version(retryVersionId, 2, List.of(), jobId)));
        when(jobs.findById(jobId)).thenReturn(Optional.of(completedJob()));
        when(jobs.hasActiveOrCompletedAnalysis(retryVersionId)).thenReturn(true);

        assertThatThrownBy(() -> service.create(requirementId,
                new RequirementFollowUpRequest(jobId, versionId, List.of(), "새 질문"), userId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(versions, never()).markCurrentVersionHistorical(any());
    }

    @Test
    void allowsAnotherFollowUpAfterFiftySavedClarifications() {
        List<Object> prior = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            prior.add(Map.of("kind", "USER_QUESTION", "question",
                    index == 0 ? "이전 질문 " + "x".repeat(25_000) : "이전 질문 " + index));
        }
        var current = version(versionId, 51, prior, jobId);
        when(versions.findCurrent(requirementId)).thenReturn(Optional.of(current));
        when(jobs.findById(jobId)).thenReturn(Optional.of(
                AnalysisJobResponse.of(jobId, projectId, versionId, "COMPLETED", 100, 100, 100, OffsetDateTime.now())));
        when(results.findByJobId(jobId)).thenReturn(Optional.of(new AnalysisResultResponse(
                jobId, projectId, "요약", "UNKNOWN", 0, Map.of(), Map.of(), OffsetDateTime.now())));
        when(versions.findNextVersionNumber(requirementId)).thenReturn(52);
        when(versions.insert(eq(requirementId), eq(52), any(), eq(userId), anyList(), eq(jobId), eq(versionId)))
                .thenReturn(version(UUID.randomUUID(), 52, List.of(), jobId));

        service.create(requirementId,
                new RequirementFollowUpRequest(jobId, versionId, List.of(), "다음 질문"), userId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object>> saved = ArgumentCaptor.forClass(List.class);
        verify(versions).insert(eq(requirementId), eq(52), any(), eq(userId), saved.capture(), eq(jobId), eq(versionId));
        assertThat(saved.getValue()).hasSize(51);
    }

    @Test
    void rejectsQuestionIndexNotPresentInStoredAnalysis() {
        when(versions.findCurrent(requirementId)).thenReturn(Optional.of(version(versionId, 1, List.of(), null)));
        when(jobs.findById(jobId)).thenReturn(Optional.of(
                AnalysisJobResponse.of(jobId, projectId, versionId, "COMPLETED", 100, 100, 100, OffsetDateTime.now())));
        when(results.findByJobId(jobId)).thenReturn(Optional.of(new AnalysisResultResponse(
                jobId, projectId, "요약", "UNKNOWN", 0,
                Map.of("databaseImpact", Map.of("openQuestions", List.of("첫 질문"))),
                Map.of(), OffsetDateTime.now())));

        assertThatThrownBy(() -> service.create(requirementId,
                new RequirementFollowUpRequest(jobId, versionId,
                        List.of(new RequirementFollowUpRequest.Answer(1, "임의 답변")), null), userId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(versions, never()).markCurrentVersionHistorical(any());
    }

    private RequirementVersion version(UUID id, int number, List<Object> clarifications, UUID sourceJobId) {
        return new RequirementVersion(id, requirementId, number, "멤버 변경", "초기 원문", "정리한 요구사항",
                List.of(), List.of(), null, clarifications, sourceJobId, null, true, userId, OffsetDateTime.now());
    }

    private AnalysisJobResponse completedJob() {
        return AnalysisJobResponse.of(jobId, projectId, versionId, "COMPLETED", 100, 100, 100, OffsetDateTime.now());
    }

    private AnalysisResultResponse resultWithQuestion(String question) {
        return new AnalysisResultResponse(jobId, projectId, "요약", "UNKNOWN", 0,
                Map.of("databaseImpact", Map.of("openQuestions", List.of(question))), Map.of(), OffsetDateTime.now());
    }
}
