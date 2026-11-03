package com.doenaai.analysis.service;

import com.doenaai.analysis.AnalysisNodeIdentity;
import com.doenaai.analysis.dto.AnalysisJobResponse;
import com.doenaai.analysis.dto.AnalysisRequirementContext;
import com.doenaai.analysis.repository.AnalysisJobRepository;
import com.doenaai.analysis.repository.AnalysisResultRepository;
import com.doenaai.project.connection.service.GitRepositoryConnectionService;
import com.doenaai.project.service.ProjectService;
import com.doenaai.requirement.entity.RequirementVersion;
import com.doenaai.requirement.service.RequirementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AnalysisRequirementContextTest {
    private final AnalysisJobRepository jobs = mock(AnalysisJobRepository.class);
    private final AnalysisResultRepository results = mock(AnalysisResultRepository.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final RequirementService requirements = mock(RequirementService.class);
    private final AnalysisJobService service = new AnalysisJobService(jobs, results, projects,
            mock(GitRepositoryConnectionService.class), requirements, mock(ObjectProvider.class),
            new AnalysisNodeIdentity(true, "test-requirement-context"));
    private final UUID projectId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID requirementId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();
    private final UUID sourceJobId = UUID.randomUUID();

    @Test
    void everyJobStateUsesItsHistoricalVersionBeforeAnyAnalysisResultExists() {
        var version = version(List.of(Map.of("kind", "USER_QUESTION", "sourceAnalysisJobId", sourceJobId.toString(),
                "question", "기존 권한에도 영향이 있나요?")), sourceJobId);
        for (String state : List.of("PENDING", "RUNNING", "FAILED", "COMPLETED", "CANCELLED")) {
            UUID jobId = UUID.randomUUID();
            var stored = AnalysisJobResponse.of(jobId, projectId, versionId, state, 20, 40, 60, null);
            when(jobs.findById(jobId)).thenReturn(Optional.of(stored));
            when(requirements.assertVersionBelongsToProject(versionId, projectId, userId)).thenReturn(version);

            var response = service.getAnalysisJob(jobId, userId);

            assertThat(response.status()).isEqualTo(state);
            assertThat(response.requirementVersionId()).isEqualTo(versionId);
            assertThat(response.requirementContext()).isEqualTo(AnalysisRequirementContext.from(version));
            assertThat(response.requirementContext().versionNumber()).isEqualTo(2);
            assertThat(response.requirementContext().originalContent()).isEqualTo("  목표 완료일을 추가해 주세요.\n원문을 유지합니다.  ");
            assertThat(response.requirementContext().latestFollowUpQuestion()).isEqualTo("기존 권한에도 영향이 있나요?");
            assertThat(stored.requirementContext()).isNull();
        }
        verify(requirements, times(5)).assertVersionBelongsToProject(versionId, projectId, userId);
        verifyNoMoreInteractions(requirements);
        verifyNoInteractions(results);
    }

    @Test
    void onlyTheLatestUserQuestionFromTheSameSourceAnalysisIsIncluded() {
        var other = UUID.randomUUID();
        var version = version(List.of(
                Map.of("kind", "USER_QUESTION", "sourceAnalysisJobId", other.toString(), "question", "이전 분석의 질문입니다."),
                Map.of("kind", "USER_QUESTION", "sourceAnalysisJobId", sourceJobId.toString(), "question", "첫 번째 추가 질문입니다."),
                Map.of("kind", "AI_QUESTION_ANSWER", "sourceAnalysisJobId", sourceJobId.toString(), "question", "AI의 확인 질문입니다."),
                Map.of("kind", "USER_QUESTION", "sourceAnalysisJobId", sourceJobId.toString(), "question", "이번에 추가한 질문입니다."),
                Map.of("kind", "USER_QUESTION", "sourceAnalysisJobId", other.toString(), "question", "다른 분석의 질문입니다.")
        ), sourceJobId);

        var context = AnalysisRequirementContext.from(version);

        assertThat(context.latestFollowUpQuestion()).isEqualTo("이번에 추가한 질문입니다.");
        assertThat(AnalysisRequirementContext.from(version(version.clarifications(), null)).latestFollowUpQuestion()).isNull();
    }

    @Test
    void contextKeepsOriginalStringsAndAcceptanceCriteriaJsonTypes() {
        var version = version(List.of(), null);
        var context = AnalysisRequirementContext.from(version);
        var mapper = JsonMapper.builder().build();
        var json = mapper.readTree(mapper.writeValueAsString(context));

        assertThat(json.get("versionId").asText()).isEqualTo(versionId.toString());
        assertThat(json.get("versionNumber").asInt()).isEqualTo(2);
        assertThat(json.get("originalContent").asText()).isEqualTo(version.originalContent());
        assertThat(context.refinedContent()).isNull();
        assertThat(context.acceptanceCriteria()).isEqualTo(version.acceptanceCriteria());
        assertThat(json.get("acceptanceCriteria").isArray()).isTrue();
        assertThat(json.get("acceptanceCriteria").get(0).isString()).isTrue();
        assertThat(json.get("acceptanceCriteria").get(1).isObject()).isTrue();
        assertThat(json.has("attachmentsMetadata")).isFalse();
        assertThat(json.has("clarifications")).isFalse();
        assertThat(json.has("createdBy")).isFalse();
    }

    @Test
    void projectStructureJobsKeepAnOptionalEmptyContextAndDoNotQueryRequirements() {
        UUID jobId = UUID.randomUUID();
        var stored = AnalysisJobResponse.of(jobId, projectId, null, "RUNNING", 0, 0, 0, null);
        when(jobs.findById(jobId)).thenReturn(Optional.of(stored));

        assertThat(service.getAnalysisJob(jobId, userId)).isSameAs(stored);
        verifyNoInteractions(requirements, results);
    }

    @Test
    void missingHistoricalVersionsDoNotHideExistingJobProgress() {
        UUID jobId = UUID.randomUUID();
        var stored = AnalysisJobResponse.of(jobId, projectId, versionId, "FAILED", 100, 50, 40, null);
        when(jobs.findById(jobId)).thenReturn(Optional.of(stored));
        when(requirements.assertVersionBelongsToProject(versionId, projectId, userId))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "과거 버전을 찾을 수 없습니다."));

        assertThat(service.getAnalysisJob(jobId, userId)).isSameAs(stored);
        verifyNoInteractions(results);
    }

    @Test
    void projectAccessDenialIsCheckedBeforeReadingVersionContent() {
        UUID jobId = UUID.randomUUID();
        when(jobs.findById(jobId)).thenReturn(Optional.of(
                AnalysisJobResponse.of(jobId, projectId, versionId, "RUNNING", 0, 0, 0, null)));
        when(projects.getProject(projectId, userId)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> service.getAnalysisJob(jobId, userId)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(requirements, results);
    }

    @Test
    void existingJobConstructorsAndFactoryRemainCompatible() {
        UUID jobId = UUID.randomUUID();
        var minimal = new AnalysisJobResponse(jobId, projectId, versionId, "PENDING", 0, 0, 0, 0,
                null, null, null, null, List.of());
        var thinking = new AnalysisJobResponse(jobId, projectId, versionId, "PENDING", 0, 0, 0, 0,
                null, null, null, null, List.of(), false);
        var effort = new AnalysisJobResponse(jobId, projectId, versionId, "PENDING", 0, 0, 0, 0,
                null, null, null, null, List.of(), false, "high");

        assertThat(minimal.requirementContext()).isNull();
        assertThat(thinking.requirementContext()).isNull();
        assertThat(thinking.thinkingEnabled()).isFalse();
        assertThat(effort.requirementContext()).isNull();
        assertThat(effort.thinkingEffort()).isEqualTo("high");
        assertThat(AnalysisJobResponse.of(jobId, projectId, versionId, "PENDING", 0, 0, 0, null).requirementContext()).isNull();
    }

    private RequirementVersion version(List<Object> clarifications, UUID sourceAnalysis) {
        return new RequirementVersion(versionId, requirementId, 2, "분석 시점의 목표 완료일 요구사항",
                "  목표 완료일을 추가해 주세요.\n원문을 유지합니다.  ", null,
                List.of("목표 완료일을 표시합니다.", Map.of("scenario", "날짜 수정", "checks", List.of("값을 보존합니다."))),
                List.of("화면 표시와 무관한 첨부 정보입니다."), null, clarifications, sourceAnalysis, null,
                false, userId, OffsetDateTime.parse("2026-11-03T12:00:00+09:00"));
    }
}
