package com.doenaai.analysis.service;

import com.doenaai.analysis.dto.AnalysisJobResponse;
import com.doenaai.analysis.repository.AnalysisJobRepository;
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
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

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
                projectService,
                gitService,
                requirementService
        );
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
                repository.commitSha()
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
                repository.commitSha()
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
                repository.commitSha()
        )).thenReturn(saved);

        var response = analysisJobService.createProjectScan(
                projectId,
                null,
                userId
        );

        assertThat(response.requirementVersionId()).isNull();
        verifyNoInteractions(requirementService);
    }
}
