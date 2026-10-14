package com.doenaai.project.service;

import com.doenaai.organization.service.OrganizationService;
import com.doenaai.project.dto.ProjectAccessContext;
import com.doenaai.project.dto.ProjectCreateRequest;
import com.doenaai.project.dto.ProjectResponse;
import com.doenaai.project.dto.ProjectUpdateRequest;
import com.doenaai.project.entity.Project;
import com.doenaai.project.repository.ProjectAccessRepository;
import com.doenaai.project.repository.ProjectRepository;
import com.doenaai.user.repository.UserAccessRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectAccessRepository projectAccessRepository;
    private final UserAccessRepository userAccessRepository;
    private final OrganizationService organizationService;

    /** userId가 접근 가능한 프로젝트만 조회해 화면용 응답 목록을 반환한다. */
    @Transactional(readOnly = true)
    public List<ProjectResponse> getProjects(
            UUID userId
    ) {
        List<Project> projects;

        if (isAdmin(userId)) {
            projects = projectRepository
                    .findAllByOrganizationMemberUserId(userId);
        } else {
            projects = projectRepository
                    .findAllByProjectAndOrganizationMemberUserId(userId);
        }

        return projects.stream()
                .map(ProjectResponse::from)
                .toList();
    }

    /** 삭제 표식이 있는 프로젝트를 복구 화면에만 반환한다. */
    @Transactional(readOnly = true)
    public List<ProjectResponse> getDeletedProjects(UUID userId) {
        List<Project> projects = isAdmin(userId)
                ? projectRepository.findDeletedByOrganizationMemberUserId(userId)
                : projectRepository.findDeletedByProjectAndOrganizationMemberUserId(userId);
        return projects.stream().map(ProjectResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse getProject(
            UUID projectId,
            UUID userId
    ) {
        Project project = findProject(projectId);

        checkTenantAccess(project, userId);

        checkReadAccess(
                projectId,
                userId
        );

        return ProjectResponse.from(project);
    }

    /** request의 조직 소속을 확인하고 프로젝트를 생성해 응답으로 반환한다. */
    @Transactional
    public ProjectResponse createProject(
            ProjectCreateRequest request,
            UUID userId
    ) {
        organizationService.assertActiveMember(
                request.organizationId(),
                userId
        );

        Project project = new Project(
                request.organizationId(),
                request.name(),
                request.description(),
                request.repositoryUrl(),
                request.defaultBranch(),
                request.sourceType(),
                userId
        );

        Project savedProject =
                projectRepository.saveAndFlush(project);

        projectAccessRepository.addOwner(
                savedProject.getId(),
                userId
        );

        return ProjectResponse.from(savedProject);
    }

    @Transactional
    public ProjectResponse updateProject(
            UUID projectId,
            ProjectUpdateRequest request,
            UUID userId
    ) {
        Project project = findProject(projectId);

        checkTenantAccess(project, userId);

        checkManageAccess(
                projectId,
                userId
        );

        if (!project.getOrganizationId().equals(
                request.organizationId()
        )) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "프로젝트의 소유 조직은 일반 수정 API에서 변경할 수 없습니다."
            );
        }

        project.update(
                request.name(),
                request.description(),
                request.repositoryUrl(),
                request.defaultBranch(),
                request.sourceType(),
                request.status()
        );

        return ProjectResponse.from(project);
    }

    /** 물리 삭제 없이 목록·새 작업 대상에서 제외한다. 관련 분석 이력은 유지된다. */
    @Transactional
    public void deleteProject(UUID projectId, UUID userId) {
        Project project = findProject(projectId);
        checkTenantAccess(project, userId);
        checkManageAccess(projectId, userId);
        project.markDeleted(userId);
    }

    /** 삭제된 프로젝트를 다시 일반 목록과 작업 대상으로 되돌린다. */
    @Transactional
    public ProjectResponse restoreProject(UUID projectId, UUID userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다."));
        if (!project.isDeleted()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "삭제된 프로젝트가 아닙니다.");
        }
        checkTenantAccess(project, userId);
        checkManageAccess(projectId, userId);
        project.restore();
        return ProjectResponse.from(project);
    }

    private Project findProject(
            UUID projectId
    ) {
        Project project = projectRepository
                .findById(projectId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "프로젝트를 찾을 수 없습니다."
                        )
                );
        if (project.isDeleted()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다.");
        }
        return project;
    }

    private void checkReadAccess(
            UUID projectId,
            UUID userId
    ) {
        if (isAdmin(userId)) {
            return;
        }

        if (projectAccessRepository
                .findMemberRole(projectId, userId)
                .isEmpty()) {
            throw new AccessDeniedException(
                    "프로젝트 조회 권한이 없습니다."
            );
        }
    }

    private void checkManageAccess(
            UUID projectId,
            UUID userId
    ) {
        if (isAdmin(userId)) {
            return;
        }

        String memberRole = projectAccessRepository
                .findMemberRole(projectId, userId)
                .orElseThrow(() ->
                        new AccessDeniedException(
                                "프로젝트 접근 권한이 없습니다."
                        )
                );

        if (!Set.of(
                "OWNER",
                "MANAGER"
        ).contains(memberRole)) {
            throw new AccessDeniedException(
                    "프로젝트 수정 권한이 없습니다."
            );
        }
    }

    private void checkTenantAccess(
            Project project,
            UUID userId
    ) {
        if (project.getOrganizationId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "프로젝트를 찾을 수 없습니다."
            );
        }

        organizationService.assertActiveMember(
                project.getOrganizationId(),
                userId
        );
    }

    @Transactional(readOnly = true)
    public void assertCanManage(
            UUID projectId,
            UUID userId
    ) {
        Project project = findProject(projectId);
        checkTenantAccess(project, userId);
        checkManageAccess(projectId, userId);
    }

    @Transactional(readOnly = true)
    public ProjectAccessContext getAccessContext(
            UUID projectId,
            UUID userId
    ) {
        Project project = findProject(projectId);
        checkTenantAccess(project, userId);

        String memberRole;

        if (isAdmin(userId)) {
            memberRole = "ADMIN";
        } else {
            memberRole = projectAccessRepository
                    .findMemberRole(projectId, userId)
                    .orElseThrow(() ->
                            new AccessDeniedException(
                                    "프로젝트 조회 권한이 없습니다."
                            )
                    );
        }

        return new ProjectAccessContext(
                project.getId(),
                project.getOrganizationId(),
                memberRole
        );
    }

    private boolean isAdmin(
            UUID userId
    ) {
        return userAccessRepository
                .findRoles(userId)
                .contains("ADMIN");
    }
}
