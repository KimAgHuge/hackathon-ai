package com.doenaai.analysis.service;

import com.doenaai.analysis.SourceEvidenceVersion;
import com.doenaai.analysis.AnalysisNodeIdentity;
import com.doenaai.analysis.dto.AnalysisJobResponse;
import com.doenaai.analysis.dto.ReusableEvidence;
import com.doenaai.analysis.repository.AnalysisJobRepository;
import com.doenaai.analysis.repository.AnalysisResultRepository;
import com.doenaai.project.connection.dto.GitRepositoryConnectionResponse;
import com.doenaai.project.connection.entity.RepositoryAuthType;
import com.doenaai.project.connection.entity.RepositoryProviderType;
import com.doenaai.project.connection.service.GitRepositoryConnectionService;
import com.doenaai.project.service.ProjectService;
import com.doenaai.requirement.service.RequirementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisJobServiceTest {

    @Mock
    private AnalysisJobRepository analysisJobRepository;

    @Mock
    private AnalysisResultRepository analysisResultRepository;

    @Mock
    private ObjectProvider<AnalysisWorker> analysisWorker;

    @Mock
    private ProjectService projectService;

    @Mock
    private GitRepositoryConnectionService gitService;

    @Mock
    private RequirementService requirementService;

    private AnalysisJobService analysisJobService;

    @BeforeEach
    void setUp() {
        analysisJobService = new AnalysisJobService(
                analysisJobRepository,
                analysisResultRepository,
                projectService,
                gitService,
                requirementService,
                analysisWorker,
                new AnalysisNodeIdentity(true, "test-backend-instance")
        );
    }

    @Test
    void disabledWorkerRejectsAnalysisBeforeReadingCredentials() {
        analysisJobService = new AnalysisJobService(analysisJobRepository, analysisResultRepository,
                projectService, gitService, requirementService, analysisWorker,
                new AnalysisNodeIdentity(false, "test-backend-instance"));

        assertThatThrownBy(() -> analysisJobService.createProjectScan(
                UUID.randomUUID(), null, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503 SERVICE_UNAVAILABLE");
        verifyNoInteractions(gitService, analysisJobRepository);
    }

    @Test
    void projectScanPersistsRequirementVersionSnapshotReference() {
        UUID projectId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        GitRepositoryConnectionResponse repository =
                GitRepositoryConnectionResponse.success(
                        "https://github.com/example/project.git",
                        "main",
                        "0123456789012345678901234567890123456789",
                        RepositoryProviderType.GITHUB,
                        RepositoryAuthType.NONE
                );
        AnalysisJobResponse saved = AnalysisJobResponse.of(
                UUID.randomUUID(),
                projectId,
                versionId,
                "PENDING",
                0,
                0,
                0,
                OffsetDateTime.now(ZoneOffset.UTC)
        );

        when(gitService.verifySavedProject(projectId, userId))
                .thenReturn(repository);
        when(analysisJobRepository.createProjectScan(
                projectId,
                versionId,
                userId,
                repository.repositoryUrl(),
                repository.branch(),
                repository.commitSha(), true, "medium"
        )).thenReturn(saved);

        var response = analysisJobService.createProjectScan(
                projectId,
                versionId,
                userId
        );

        assertThat(response.requirementVersionId())
                .isEqualTo(versionId);
        verify(requirementService).assertVersionBelongsToProject(
                versionId,
                projectId,
                userId
        );
        verify(analysisJobRepository).createProjectScan(
                projectId,
                versionId,
                userId,
                repository.repositoryUrl(),
                repository.branch(),
                repository.commitSha(), true, "medium"
        );
    }

    @Test
    void mismatchedRequirementVersionStopsBeforeGitVerification() {
        UUID projectId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ResponseStatusException denial = new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Requirement version not found."
        );

        doThrow(denial)
                .when(requirementService)
                .assertVersionBelongsToProject(
                        versionId,
                        projectId,
                        userId
                );

        assertThatThrownBy(() ->
                analysisJobService.createProjectScan(
                        projectId,
                        versionId,
                        userId
                )
        ).isSameAs(denial);

        verifyNoInteractions(gitService, analysisJobRepository);
    }

    @Test
    void legacyProjectScanCanOmitRequirementVersion() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        GitRepositoryConnectionResponse repository =
                GitRepositoryConnectionResponse.success(
                        "https://github.com/example/project.git",
                        "main",
                        "0123456789012345678901234567890123456789",
                        RepositoryProviderType.GITHUB,
                        RepositoryAuthType.NONE
                );
        AnalysisJobResponse saved = AnalysisJobResponse.of(
                UUID.randomUUID(),
                projectId,
                null,
                "PENDING",
                0,
                0,
                0,
                OffsetDateTime.now(ZoneOffset.UTC)
        );

        when(gitService.verifySavedProject(projectId, userId))
                .thenReturn(repository);
        when(analysisJobRepository.createProjectScan(
                projectId,
                null,
                userId,
                repository.repositoryUrl(),
                repository.branch(),
                repository.commitSha(), true, "medium"
        )).thenReturn(saved);

        var response = analysisJobService.createProjectScan(
                projectId,
                null,
                userId
        );

        assertThat(response.requirementVersionId()).isNull();
        verifyNoInteractions(requirementService);
    }

    @Test
    void savedEvidenceStartsNewAnalysisWithoutContactingGit() {
        UUID projectId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID sourceJobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String sha = "a".repeat(40);
        var evidence = new ReusableEvidence(sourceJobId,
                Map.of("commitSha", sha, "components", List.of(), "promptCoverage", Map.of("truncated", false),
                        "collectorVersion", SourceEvidenceVersion.CURRENT,
                        "scanMetrics", Map.of("truncated", false)),
                Map.of("truncated", false, "warnings", List.of(), "tables", List.of()), List.of());
        var saved = AnalysisJobResponse.of(UUID.randomUUID(), projectId, versionId, "PENDING", 0, 0, 0,
                OffsetDateTime.now(ZoneOffset.UTC));
        when(analysisResultRepository.findReusableEvidenceByJobId(projectId, sourceJobId))
                .thenReturn(Optional.of(evidence));
        when(analysisJobRepository.createFromSavedEvidence(projectId, versionId, userId, sourceJobId, true, "medium"))
                .thenReturn(saved);

        assertThat(analysisJobService.createProjectScan(projectId, versionId, userId, sourceJobId, true))
                .isSameAs(saved);
        verifyNoInteractions(gitService);
        verify(requirementService).assertVersionBelongsToProject(versionId, projectId, userId);
    }

    @Test
    void unavailableSavedEvidenceIsRejectedWithoutGitOrJobCreation() {
        UUID projectId = UUID.randomUUID();
        UUID sourceJobId = UUID.randomUUID();
        when(analysisResultRepository.findReusableEvidenceByJobId(projectId, sourceJobId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> analysisJobService.createProjectScan(projectId, null, UUID.randomUUID(), sourceJobId, true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("저장 근거");
        verifyNoInteractions(gitService, analysisJobRepository);
    }

    @Test
    void missingCoreSavedEvidenceReturnsARecollectionGuideWithoutCreatingAJob() {
        var projectId = UUID.randomUUID();
        var sourceJobId = UUID.randomUUID();
        when(analysisResultRepository.findReusableEvidenceByJobId(projectId, sourceJobId)).thenReturn(Optional.of(
                new ReusableEvidence(sourceJobId, Map.of("commitSha", "a".repeat(40),
                        "promptCoverage", Map.of("truncated", false), "scanMetrics", Map.of("truncated", false)),
                        Map.of("truncated", false, "warnings", List.of()), List.of())));

        assertThatThrownBy(() -> analysisJobService.createProjectScan(projectId, null, UUID.randomUUID(), sourceJobId, true))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("최신 Git·DB 확인");
        verifyNoInteractions(gitService, analysisJobRepository);
    }

    @Test
    void savedEvidenceRequiresExplicitSnapshotConfirmation() {
        UUID projectId = UUID.randomUUID();
        UUID sourceJobId = UUID.randomUUID();

        assertThatThrownBy(() -> analysisJobService.createProjectScan(projectId, null,
                UUID.randomUUID(), sourceJobId, false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("선택한 시점의 저장 근거를 사용한다는 확인");
        verifyNoInteractions(analysisResultRepository, gitService, analysisJobRepository);
    }

    @Test
    void cancellingActiveJobReturnsCancelledState() {
        UUID jobId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var pending = AnalysisJobResponse.of(jobId, projectId, null, "PENDING", 0, 0, 0,
                OffsetDateTime.now(ZoneOffset.UTC));
        var cancelled = AnalysisJobResponse.of(jobId, projectId, null, "CANCELLED", 0, 0, 0,
                pending.createdAt());
        when(analysisJobRepository.findById(jobId)).thenReturn(Optional.of(pending), Optional.of(cancelled));
        when(analysisJobRepository.cancel(jobId)).thenReturn(true);

        assertThat(analysisJobService.cancelAnalysisJob(jobId, userId).status()).isEqualTo("CANCELLED");
        verify(projectService).assertCanManage(projectId, userId);
    }
}
