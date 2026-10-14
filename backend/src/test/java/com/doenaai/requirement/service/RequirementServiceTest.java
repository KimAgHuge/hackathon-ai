package com.doenaai.requirement.service;

import com.doenaai.project.dto.ProjectAccessContext;
import com.doenaai.project.dto.ProjectResponse;
import com.doenaai.project.service.ProjectService;
import com.doenaai.requirement.dto.RequirementCreateRequest;
import com.doenaai.requirement.dto.RequirementStatusUpdateRequest;
import com.doenaai.requirement.dto.RequirementVersionCreateRequest;
import com.doenaai.requirement.entity.Requirement;
import com.doenaai.requirement.entity.RequirementPriority;
import com.doenaai.requirement.entity.RequirementSourceType;
import com.doenaai.requirement.entity.RequirementStatus;
import com.doenaai.requirement.entity.RequirementVersion;
import com.doenaai.requirement.repository.RequirementRepository;
import com.doenaai.requirement.repository.RequirementVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequirementServiceTest {

    @Mock
    private RequirementRepository requirementRepository;

    @Mock
    private RequirementVersionRepository versionRepository;

    @Mock
    private ProjectService projectService;

    private RequirementService requirementService;

    @BeforeEach
    void setUp() {
        requirementService = new RequirementService(
                requirementRepository,
                versionRepository,
                projectService
        );
    }

    @Test
    void allRequirementsReadOnlyAccessibleProjects() {
        UUID userId = UUID.randomUUID();
        UUID firstProjectId = UUID.randomUUID();
        UUID secondProjectId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        when(projectService.getProjects(userId)).thenReturn(List.of(
                new ProjectResponse(firstProjectId, organizationId, "첫 프로젝트", null,
                        null, null, null, null, null, null, null, null),
                new ProjectResponse(secondProjectId, organizationId, "둘째 프로젝트", null,
                        null, null, null, null, null, null, null, null)
        ));
        when(projectService.getAccessContext(firstProjectId, userId))
                .thenReturn(new ProjectAccessContext(firstProjectId, organizationId, "OWNER"));
        when(projectService.getAccessContext(secondProjectId, userId))
                .thenReturn(new ProjectAccessContext(secondProjectId, organizationId, "CUSTOMER"));

        assertThat(requirementService.getAllRequirements(userId)).isEmpty();

        verify(requirementRepository).findAllByProjectIdOrderByCreatedAtDesc(firstProjectId);
        verify(requirementRepository).findAllVisibleToCustomer(secondProjectId, userId);
    }

    @Test
    void ownerCreatesRequirementAndInitialSnapshotFromProjectScope() {
        UUID projectId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID requirementId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        stubAccess(projectId, organizationId, userId, "OWNER");
        when(requirementRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> {
                    Requirement requirement = invocation.getArgument(0);
                    ReflectionTestUtils.setField(
                            requirement,
                            "id",
                            requirementId
                    );
                    return requirement;
                });
        when(versionRepository.insert(
                eq(requirementId),
                eq(1),
                any(),
                eq(userId)
        )).thenReturn(version(requirementId, 1, true, userId));

        var response = requirementService.createRequirement(
                projectId,
                createRequest(false),
                userId
        );

        assertThat(response.organizationId())
                .isEqualTo(organizationId);
        assertThat(response.projectId()).isEqualTo(projectId);
        assertThat(response.currentVersion().versionNumber())
                .isEqualTo(1);
        assertThat(response.currentVersion().current()).isTrue();

        ArgumentCaptor<Requirement> requirementCaptor =
                ArgumentCaptor.forClass(Requirement.class);
        verify(requirementRepository)
                .saveAndFlush(requirementCaptor.capture());

        Requirement saved = requirementCaptor.getValue();
        assertThat(saved.getOrganizationId())
                .isEqualTo(organizationId);
        assertThat(saved.getProjectId()).isEqualTo(projectId);
        assertThat(saved.getSourceType())
                .isEqualTo(RequirementSourceType.PM);
    }

    @Test
    void developerCannotCreateRequirement() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        stubAccess(
                projectId,
                UUID.randomUUID(),
                userId,
                "DEVELOPER"
        );

        assertThatThrownBy(() ->
                requirementService.createRequirement(
                        projectId,
                        createRequest(false),
                        userId
                )
        ).isInstanceOf(AccessDeniedException.class);

        verify(requirementRepository, never())
                .saveAndFlush(any());
    }

    @Test
    void customerCannotPublishRequirementDuringCreation() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        stubAccess(
                projectId,
                UUID.randomUUID(),
                userId,
                "CUSTOMER"
        );

        assertThatThrownBy(() ->
                requirementService.createRequirement(
                        projectId,
                        createRequest(true),
                        userId
                )
        ).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void tenantDenialStopsRequirementCreation() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ResponseStatusException denial = new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Project not found."
        );

        when(projectService.getAccessContext(projectId, userId))
                .thenThrow(denial);

        assertThatThrownBy(() ->
                requirementService.createRequirement(
                        projectId,
                        createRequest(false),
                        userId
                )
        ).isSameAs(denial);

        verify(requirementRepository, never())
                .saveAndFlush(any());
        verify(versionRepository, never())
                .insert(any(), eq(1), any(), any());
    }

    @Test
    void customerListUsesVisibilityFilterAndSanitizesInternalFields() {
        UUID projectId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Requirement requirement = requirement(
                UUID.randomUUID(),
                organizationId,
                projectId,
                userId,
                RequirementStatus.DRAFT,
                true
        );
        RequirementVersion version = version(
                requirement.getId(),
                1,
                true,
                UUID.randomUUID()
        );

        stubAccess(
                projectId,
                organizationId,
                userId,
                "CUSTOMER"
        );
        when(requirementRepository.findAllVisibleToCustomer(
                projectId,
                userId
        )).thenReturn(List.of(requirement));
        when(versionRepository.findCurrent(requirement.getId()))
                .thenReturn(Optional.of(version));

        var response = requirementService.getRequirements(
                projectId,
                userId
        ).getFirst();

        assertThat(response.sourceRef()).isNull();
        assertThat(response.requestedBy()).isNull();
        assertThat(response.createdBy()).isNull();
        assertThat(response.currentVersion().changeNote()).isNull();
        assertThat(response.currentVersion().createdBy()).isNull();
        assertThat(response.currentVersion().attachmentsMetadata())
                .isEmpty();
        verify(requirementRepository, never())
                .findAllByProjectIdOrderByCreatedAtDesc(any());
    }

    @Test
    void customerCannotReadInvisibleRequirementRequestedByAnotherUser() {
        UUID projectId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Requirement requirement = requirement(
                UUID.randomUUID(),
                organizationId,
                projectId,
                UUID.randomUUID(),
                RequirementStatus.DRAFT,
                false
        );

        when(requirementRepository.findById(requirement.getId()))
                .thenReturn(Optional.of(requirement));
        stubAccess(
                projectId,
                organizationId,
                userId,
                "CUSTOMER"
        );

        assertThatThrownBy(() -> requirementService.getRequirement(
                requirement.getId(),
                userId
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception)
                                .getStatusCode()
                ).isEqualTo(HttpStatus.NOT_FOUND));

        verify(versionRepository, never()).findCurrent(any());
    }

    @Test
    void versionCreationLocksParentAndReplacesCurrentSnapshot() {
        UUID projectId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Requirement requirement = requirement(
                UUID.randomUUID(),
                organizationId,
                projectId,
                userId,
                RequirementStatus.REFINING,
                false
        );
        RequirementVersionCreateRequest request = versionRequest();
        RequirementVersion secondVersion = version(
                requirement.getId(),
                2,
                true,
                userId
        );

        when(requirementRepository.findById(requirement.getId()))
                .thenReturn(Optional.of(requirement));
        stubAccess(projectId, organizationId, userId, "PM");
        when(versionRepository.findNextVersionNumber(
                requirement.getId()
        )).thenReturn(2);
        when(versionRepository.insert(
                requirement.getId(),
                2,
                request,
                userId
        )).thenReturn(secondVersion);
        when(requirementRepository.saveAndFlush(requirement))
                .thenReturn(requirement);

        var response = requirementService.createVersion(
                requirement.getId(),
                request,
                userId
        );

        assertThat(response.versionNumber()).isEqualTo(2);
        assertThat(requirement.getTitle())
                .isEqualTo(request.title());

        InOrder order = inOrder(versionRepository);
        order.verify(versionRepository)
                .lockRequirement(requirement.getId());
        order.verify(versionRepository)
                .findNextVersionNumber(requirement.getId());
        order.verify(versionRepository)
                .markCurrentVersionHistorical(requirement.getId());
        order.verify(versionRepository).insert(
                requirement.getId(),
                2,
                request,
                userId
        );
    }

    @Test
    void customerCannotCreateRefinedVersion() {
        UUID projectId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Requirement requirement = requirement(
                UUID.randomUUID(),
                organizationId,
                projectId,
                userId,
                RequirementStatus.DRAFT,
                false
        );

        when(requirementRepository.findById(requirement.getId()))
                .thenReturn(Optional.of(requirement));
        stubAccess(
                projectId,
                organizationId,
                userId,
                "CUSTOMER"
        );

        assertThatThrownBy(() -> requirementService.createVersion(
                requirement.getId(),
                versionRequest(),
                userId
        )).isInstanceOf(AccessDeniedException.class);

        verify(versionRepository, never()).lockRequirement(any());
    }

    @Test
    void customerCanSubmitOwnDraft() {
        UUID projectId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Requirement requirement = requirement(
                UUID.randomUUID(),
                organizationId,
                projectId,
                userId,
                RequirementStatus.DRAFT,
                false
        );

        when(requirementRepository.findById(requirement.getId()))
                .thenReturn(Optional.of(requirement));
        stubAccess(
                projectId,
                organizationId,
                userId,
                "CUSTOMER"
        );
        when(requirementRepository.saveAndFlush(requirement))
                .thenReturn(requirement);
        when(versionRepository.findCurrent(requirement.getId()))
                .thenReturn(Optional.of(version(
                        requirement.getId(),
                        1,
                        true,
                        userId
                )));

        var response = requirementService.updateStatus(
                requirement.getId(),
                new RequirementStatusUpdateRequest(
                        RequirementStatus.SUBMITTED,
                        null
                ),
                userId
        );

        assertThat(response.status())
                .isEqualTo(RequirementStatus.SUBMITTED);
    }

    @Test
    void invalidWorkflowTransitionReturnsConflict() {
        UUID projectId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Requirement requirement = requirement(
                UUID.randomUUID(),
                organizationId,
                projectId,
                userId,
                RequirementStatus.DRAFT,
                false
        );

        when(requirementRepository.findById(requirement.getId()))
                .thenReturn(Optional.of(requirement));
        stubAccess(projectId, organizationId, userId, "OWNER");

        assertThatThrownBy(() -> requirementService.updateStatus(
                requirement.getId(),
                new RequirementStatusUpdateRequest(
                        RequirementStatus.APPROVED,
                        null
                ),
                userId
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception)
                                .getStatusCode()
                ).isEqualTo(HttpStatus.CONFLICT));

        verify(requirementRepository, never())
                .saveAndFlush(requirement);
    }

    @Test
    void analysisCannotUseVersionFromAnotherProjectOrOrganization() {
        UUID requirementProjectId = UUID.randomUUID();
        UUID requestedProjectId = UUID.randomUUID();
        UUID requirementOrganizationId = UUID.randomUUID();
        UUID requestedOrganizationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Requirement requirement = requirement(
                UUID.randomUUID(),
                requirementOrganizationId,
                requirementProjectId,
                userId,
                RequirementStatus.READY,
                false
        );
        RequirementVersion version = version(
                requirement.getId(),
                1,
                true,
                userId
        );

        stubAccess(
                requestedProjectId,
                requestedOrganizationId,
                userId,
                "OWNER"
        );
        when(versionRepository.findById(version.id()))
                .thenReturn(Optional.of(version));
        when(requirementRepository.findById(requirement.getId()))
                .thenReturn(Optional.of(requirement));

        assertThatThrownBy(() ->
                requirementService.assertVersionBelongsToProject(
                        version.id(),
                        requestedProjectId,
                        userId
                )
        )
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception)
                                .getStatusCode()
                ).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private void stubAccess(
            UUID projectId,
            UUID organizationId,
            UUID userId,
            String role
    ) {
        when(projectService.getAccessContext(projectId, userId))
                .thenReturn(new ProjectAccessContext(
                        projectId,
                        organizationId,
                        role
                ));
    }

    private RequirementCreateRequest createRequest(
            boolean customerVisible
    ) {
        return new RequirementCreateRequest(
                "REQ-001",
                "Login requirement",
                null,
                "customer-email",
                RequirementPriority.HIGH,
                customerVisible,
                "Users must be able to log in.",
                "Implement OAuth login.",
                List.of("Login succeeds"),
                List.of(Map.of("name", "request.txt")),
                "Initial request"
        );
    }

    private RequirementVersionCreateRequest versionRequest() {
        return new RequirementVersionCreateRequest(
                "Refined login requirement",
                "Users must be able to log in.",
                "Use the existing identity provider.",
                List.of("Successful users reach the dashboard"),
                List.of(),
                "Clarified acceptance criteria"
        );
    }

    private Requirement requirement(
            UUID requirementId,
            UUID organizationId,
            UUID projectId,
            UUID createdBy,
            RequirementStatus status,
            boolean customerVisible
    ) {
        Requirement requirement = new Requirement(
                organizationId,
                projectId,
                "REQ-001",
                "Login requirement",
                RequirementSourceType.CUSTOMER,
                "customer-email",
                RequirementPriority.HIGH,
                customerVisible,
                createdBy
        );

        ReflectionTestUtils.setField(
                requirement,
                "id",
                requirementId
        );
        ReflectionTestUtils.setField(
                requirement,
                "status",
                status
        );

        return requirement;
    }

    private RequirementVersion version(
            UUID requirementId,
            int versionNumber,
            boolean current,
            UUID createdBy
    ) {
        return new RequirementVersion(
                UUID.randomUUID(),
                requirementId,
                versionNumber,
                "Login requirement",
                "Users must be able to log in.",
                "Use OAuth.",
                List.of("Login succeeds"),
                List.of(Map.of("uri", "internal://request.txt")),
                "Internal refinement note",
                current,
                createdBy,
                OffsetDateTime.now(ZoneOffset.UTC)
        );
    }
}
