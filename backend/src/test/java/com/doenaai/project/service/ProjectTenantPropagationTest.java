package com.doenaai.project.service;

import com.doenaai.analysis.repository.AnalysisJobRepository;
import com.doenaai.analysis.service.AnalysisJobService;
import com.doenaai.project.connection.repository.GitHostPolicyRepository;
import com.doenaai.project.connection.repository.ProjectDatabaseSourceRepository;
import com.doenaai.project.connection.repository.ProjectRepositoryConnectionRepository;
import com.doenaai.project.connection.security.CredentialCipher;
import com.doenaai.project.connection.service.DatabaseConnectionService;
import com.doenaai.project.connection.service.GitRepositoryConnectionService;
import com.doenaai.requirement.service.RequirementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ProjectTenantPropagationTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private ProjectRepositoryConnectionRepository
            repositoryConnectionRepository;

    @Mock
    private GitHostPolicyRepository hostPolicyRepository;

    @Mock
    private ProjectDatabaseSourceRepository databaseSourceRepository;

    @Mock
    private CredentialCipher credentialCipher;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private AnalysisJobRepository analysisJobRepository;

    @Mock
    private RequirementService requirementService;

    private GitRepositoryConnectionService gitService;
    private DatabaseConnectionService databaseService;
    private AnalysisJobService analysisService;

    @BeforeEach
    void setUp() {
        gitService = new GitRepositoryConnectionService(
                projectService,
                repositoryConnectionRepository,
                hostPolicyRepository,
                credentialCipher,
                objectMapper,
                10
        );
        databaseService = new DatabaseConnectionService(
                projectService,
                databaseSourceRepository,
                credentialCipher
        );
        analysisService = new AnalysisJobService(
                analysisJobRepository,
                projectService,
                gitService,
                requirementService
        );
    }

    @Test
    void tenantDenialStopsGitConnectionSave() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ResponseStatusException denial = tenantDenial(
                projectId,
                userId
        );

        assertThatThrownBy(() -> gitService.saveConnection(
                projectId,
                null,
                userId
        )).isSameAs(denial);

        verifyNoInteractions(
                repositoryConnectionRepository,
                hostPolicyRepository,
                credentialCipher,
                objectMapper
        );
    }

    @Test
    void tenantDenialStopsDatabaseConnectionSave() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ResponseStatusException denial = tenantDenial(
                projectId,
                userId
        );

        assertThatThrownBy(() -> databaseService.saveConnection(
                projectId,
                null,
                userId
        )).isSameAs(denial);

        verifyNoInteractions(
                databaseSourceRepository,
                credentialCipher
        );
    }

    @Test
    void tenantDenialStopsAnalysisJobCreation() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ResponseStatusException denial = tenantDenial(
                projectId,
                userId
        );

        assertThatThrownBy(() -> analysisService.createProjectScan(
                projectId,
                UUID.randomUUID(),
                userId
        )).isSameAs(denial);

        verifyNoInteractions(analysisJobRepository);
    }

    private ResponseStatusException tenantDenial(
            UUID projectId,
            UUID userId
    ) {
        ResponseStatusException denial = new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Project not found."
        );

        doThrow(denial)
                .when(projectService)
                .assertCanManage(projectId, userId);

        return denial;
    }
}
