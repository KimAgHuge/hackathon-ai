package com.doenaai.project.service;

import com.doenaai.organization.service.OrganizationService;
import com.doenaai.project.dto.ProjectCreateRequest;
import com.doenaai.project.dto.ProjectUpdateRequest;
import com.doenaai.project.entity.Project;
import com.doenaai.project.entity.ProjectSourceType;
import com.doenaai.project.entity.ProjectStatus;
import com.doenaai.project.repository.ProjectAccessRepository;
import com.doenaai.project.repository.ProjectRepository;
import com.doenaai.user.repository.UserAccessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectAccessRepository projectAccessRepository;

    @Mock
    private UserAccessRepository userAccessRepository;

    @Mock
    private OrganizationService organizationService;

    private ProjectService projectService;

    @BeforeEach
    void setUp() {
        projectService = new ProjectService(
                projectRepository,
                projectAccessRepository,
                userAccessRepository,
                organizationService
        );
    }

    @Test
    void activeOrganizationMemberCanCreateProject() {
        UUID organizationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Project project = project(
                UUID.randomUUID(),
                organizationId,
                userId
        );

        when(projectRepository.saveAndFlush(any()))
                .thenReturn(project);

        var response = projectService.createProject(
                createRequest(organizationId),
                userId
        );

        assertThat(response.organizationId())
                .isEqualTo(organizationId);
        verify(organizationService).assertActiveMember(
                organizationId,
                userId
        );
        verify(projectAccessRepository).addOwner(
                project.getId(),
                userId
        );
    }

    @Test
    void nonOrganizationMemberCannotCreateProject() {
        UUID organizationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ResponseStatusException notFound = new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Organization not found."
        );

        doThrow(notFound)
                .when(organizationService)
                .assertActiveMember(organizationId, userId);

        assertThatThrownBy(() -> projectService.createProject(
                createRequest(organizationId),
                userId
        )).isSameAs(notFound);

        verify(projectRepository, never()).saveAndFlush(any());
        verify(projectAccessRepository, never())
                .addOwner(any(), any());
    }

    @Test
    void projectReadRequiresOrganizationMembershipBeforeProjectRole() {
        UUID organizationId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Project project = project(projectId, organizationId, userId);
        ResponseStatusException notFound = new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Organization not found."
        );

        when(projectRepository.findById(projectId))
                .thenReturn(Optional.of(project));
        doThrow(notFound)
                .when(organizationService)
                .assertActiveMember(organizationId, userId);

        assertThatThrownBy(() ->
                projectService.getProject(projectId, userId)
        ).isSameAs(notFound);

        verify(projectAccessRepository, never())
                .findMemberRole(any(), any());
        verify(userAccessRepository, never()).findRoles(any());
    }

    @Test
    void activeOrganizationAndProjectMemberCanReadProject() {
        UUID organizationId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Project project = project(projectId, organizationId, userId);

        when(projectRepository.findById(projectId))
                .thenReturn(Optional.of(project));
        when(userAccessRepository.findRoles(userId))
                .thenReturn(Set.of());
        when(projectAccessRepository.findMemberRole(projectId, userId))
                .thenReturn(Optional.of("MEMBER"));

        var response = projectService.getProject(projectId, userId);

        assertThat(response.id()).isEqualTo(projectId);
        assertThat(response.organizationId())
                .isEqualTo(organizationId);
    }

    @Test
    void updateCannotMoveProjectToAnotherOrganization() {
        UUID organizationId = UUID.randomUUID();
        UUID otherOrganizationId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Project project = project(projectId, organizationId, userId);

        when(projectRepository.findById(projectId))
                .thenReturn(Optional.of(project));
        when(userAccessRepository.findRoles(userId))
                .thenReturn(Set.of());
        when(projectAccessRepository.findMemberRole(projectId, userId))
                .thenReturn(Optional.of("OWNER"));

        assertThatThrownBy(() -> projectService.updateProject(
                projectId,
                updateRequest(otherOrganizationId),
                userId
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception)
                                .getStatusCode()
                ).isEqualTo(HttpStatus.CONFLICT));

        assertThat(project.getName()).isEqualTo("Project");
    }

    @Test
    void activeOrganizationOwnerCanUpdateProject() {
        UUID organizationId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Project project = project(projectId, organizationId, userId);

        when(projectRepository.findById(projectId))
                .thenReturn(Optional.of(project));
        when(userAccessRepository.findRoles(userId))
                .thenReturn(Set.of());
        when(projectAccessRepository.findMemberRole(projectId, userId))
                .thenReturn(Optional.of("OWNER"));

        var response = projectService.updateProject(
                projectId,
                updateRequest(organizationId),
                userId
        );

        assertThat(response.organizationId())
                .isEqualTo(organizationId);
        assertThat(response.name()).isEqualTo("Updated project");
    }

    @Test
    void adminListIsStillLimitedToActiveOrganizations() {
        UUID userId = UUID.randomUUID();

        when(userAccessRepository.findRoles(userId))
                .thenReturn(Set.of("ADMIN"));
        when(projectRepository
                .findAllByOrganizationMemberUserId(userId))
                .thenReturn(List.of());

        assertThat(projectService.getProjects(userId)).isEmpty();

        verify(projectRepository)
                .findAllByOrganizationMemberUserId(userId);
        verify(projectRepository, never())
                .findAllByProjectAndOrganizationMemberUserId(any());
    }

    @Test
    void memberListRequiresBothProjectAndOrganizationMembership() {
        UUID userId = UUID.randomUUID();

        when(userAccessRepository.findRoles(userId))
                .thenReturn(Set.of());
        when(projectRepository
                .findAllByProjectAndOrganizationMemberUserId(userId))
                .thenReturn(List.of());

        assertThat(projectService.getProjects(userId)).isEmpty();

        verify(projectRepository)
                .findAllByProjectAndOrganizationMemberUserId(userId);
        verify(projectRepository, never())
                .findAllByOrganizationMemberUserId(any());
    }

    @Test
    void sharedManageGuardChecksTenantBeforeProjectRole() {
        UUID organizationId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Project project = project(projectId, organizationId, userId);

        when(projectRepository.findById(projectId))
                .thenReturn(Optional.of(project));
        when(userAccessRepository.findRoles(userId))
                .thenReturn(Set.of());
        when(projectAccessRepository.findMemberRole(projectId, userId))
                .thenReturn(Optional.of("MANAGER"));

        projectService.assertCanManage(projectId, userId);

        InOrder order = inOrder(
                organizationService,
                projectAccessRepository
        );
        order.verify(organizationService)
                .assertActiveMember(organizationId, userId);
        order.verify(projectAccessRepository)
                .findMemberRole(projectId, userId);
    }

    private ProjectCreateRequest createRequest(
            UUID organizationId
    ) {
        return new ProjectCreateRequest(
                organizationId,
                "Project",
                "Description",
                "https://example.com/project.git",
                "main",
                ProjectSourceType.GIT
        );
    }

    private ProjectUpdateRequest updateRequest(
            UUID organizationId
    ) {
        return new ProjectUpdateRequest(
                organizationId,
                "Updated project",
                "Updated description",
                "https://example.com/project.git",
                "main",
                ProjectSourceType.GIT,
                ProjectStatus.ACTIVE
        );
    }

    private Project project(
            UUID projectId,
            UUID organizationId,
            UUID createdBy
    ) {
        Project project = new Project(
                organizationId,
                "Project",
                "Description",
                "https://example.com/project.git",
                "main",
                ProjectSourceType.GIT,
                createdBy
        );

        ReflectionTestUtils.setField(project, "id", projectId);

        return project;
    }
}
