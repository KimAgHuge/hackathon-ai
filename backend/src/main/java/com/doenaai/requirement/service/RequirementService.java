package com.doenaai.requirement.service;

import com.doenaai.project.dto.ProjectAccessContext;
import com.doenaai.project.service.ProjectService;
import com.doenaai.requirement.dto.RequirementCreateRequest;
import com.doenaai.requirement.dto.RequirementResponse;
import com.doenaai.requirement.dto.RequirementStatusUpdateRequest;
import com.doenaai.requirement.dto.RequirementVersionCreateRequest;
import com.doenaai.requirement.dto.RequirementVersionResponse;
import com.doenaai.requirement.entity.Requirement;
import com.doenaai.requirement.entity.RequirementSourceType;
import com.doenaai.requirement.entity.RequirementStatus;
import com.doenaai.requirement.entity.RequirementVersion;
import com.doenaai.requirement.repository.RequirementRepository;
import com.doenaai.requirement.repository.RequirementVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RequirementService {

    private static final Set<String> REQUIREMENT_CREATORS = Set.of(
            "ADMIN",
            "OWNER",
            "MANAGER",
            "PM",
            "PL",
            "CUSTOMER"
    );

    private static final Set<String> VERSION_EDITORS = Set.of(
            "ADMIN",
            "OWNER",
            "MANAGER",
            "PM",
            "PL"
    );

    private static final Set<String> WORKFLOW_MANAGERS = Set.of(
            "ADMIN",
            "OWNER",
            "MANAGER",
            "PM",
            "PL"
    );

    private static final Map<RequirementStatus, Set<RequirementStatus>>
            ALLOWED_TRANSITIONS = transitions();

    private final RequirementRepository requirementRepository;
    private final RequirementVersionRepository versionRepository;
    private final ProjectService projectService;

    /** projectId에 요구사항과 첫 버전을 만들고 userId 권한에 맞는 응답을 반환한다. */
    @Transactional
    public RequirementResponse createRequirement(
            UUID projectId,
            RequirementCreateRequest request,
            UUID userId
    ) {
        ProjectAccessContext access = projectService
                .getAccessContext(projectId, userId);
        assertRole(
                access,
                REQUIREMENT_CREATORS,
                "요구사항 생성 권한이 없습니다."
        );

        boolean customer = access.hasRole("CUSTOMER");

        if (customer && request.customerVisible()) {
            throw new AccessDeniedException(
                    "고객은 요구사항 공개 범위를 직접 변경할 수 없습니다."
            );
        }

        RequirementSourceType sourceType = sourceTypeFor(
                access,
                request.sourceType()
        );
        Requirement requirement = new Requirement(
                access.organizationId(),
                projectId,
                request.requirementKey(),
                request.title(),
                sourceType,
                request.sourceRef(),
                request.priority(),
                !customer && request.customerVisible(),
                userId
        );

        try {
            Requirement saved = requirementRepository
                    .saveAndFlush(requirement);
            RequirementVersion version = versionRepository.insert(
                    saved.getId(),
                    1,
                    initialVersionRequest(request),
                    userId
            );

            return RequirementResponse.from(
                    saved,
                    version,
                    customer
            );
        } catch (DataIntegrityViolationException exception) {
            throw conflict(
                    "같은 프로젝트에서 이미 사용 중인 요구사항 키이거나 버전입니다."
            );
        }
    }

    /** 프로젝트에서 userId가 볼 수 있는 요구사항과 현재 버전 목록을 반환한다. */
    @Transactional(readOnly = true)
    public List<RequirementResponse> getRequirements(
            UUID projectId,
            UUID userId
    ) {
        ProjectAccessContext access = projectService
                .getAccessContext(projectId, userId);
        boolean customer = access.hasRole("CUSTOMER");
        List<Requirement> requirements = customer
                ? requirementRepository.findAllVisibleToCustomer(
                        projectId,
                        userId
                )
                : requirementRepository
                        .findAllByProjectIdOrderByCreatedAtDesc(projectId);

        return requirements.stream()
                .map(requirement -> toResponse(
                        requirement,
                        customer
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public RequirementResponse getRequirement(
            UUID requirementId,
            UUID userId
    ) {
        Requirement requirement = findRequirement(requirementId);
        ProjectAccessContext access = projectService.getAccessContext(
                requirement.getProjectId(),
                userId
        );
        boolean customer = access.hasRole("CUSTOMER");

        assertCustomerCanSee(requirement, customer, userId);

        return toResponse(requirement, customer);
    }

    /** 기존 요구사항에 새 버전을 추가하고 그 버전을 현재 버전으로 지정한다. */
    @Transactional
    public RequirementVersionResponse createVersion(
            UUID requirementId,
            RequirementVersionCreateRequest request,
            UUID userId
    ) {
        Requirement requirement = findRequirement(requirementId);
        ProjectAccessContext access = projectService.getAccessContext(
                requirement.getProjectId(),
                userId
        );
        assertRole(
                access,
                VERSION_EDITORS,
                "요구사항 버전 생성 권한이 없습니다."
        );

        try {
            versionRepository.lockRequirement(requirementId);
            int nextVersion = versionRepository
                    .findNextVersionNumber(requirementId);
            versionRepository.markCurrentVersionHistorical(
                    requirementId
            );
            RequirementVersion version = versionRepository.insert(
                    requirementId,
                    nextVersion,
                    request,
                    userId
            );

            requirement.updateCurrentTitle(request.title());
            requirementRepository.saveAndFlush(requirement);

            return RequirementVersionResponse.from(version, false);
        } catch (DataIntegrityViolationException exception) {
            throw conflict(
                    "요구사항 버전이 동시에 변경되었습니다. 다시 시도해주세요."
            );
        }
    }

    @Transactional
    public RequirementResponse updateStatus(
            UUID requirementId,
            RequirementStatusUpdateRequest request,
            UUID userId
    ) {
        Requirement requirement = findRequirement(requirementId);
        ProjectAccessContext access = projectService.getAccessContext(
                requirement.getProjectId(),
                userId
        );

        assertTransitionAllowed(
                requirement.getStatus(),
                request.status()
        );
        assertCanChangeStatus(
                requirement,
                access,
                request,
                userId
        );

        requirement.updateStatus(
                request.status(),
                request.customerVisible()
        );
        Requirement saved = requirementRepository
                .saveAndFlush(requirement);

        return toResponse(
                saved,
                access.hasRole("CUSTOMER")
        );
    }

    /** versionId가 해당 projectId에 속하고 userId가 접근 가능한지 확인한 뒤 버전을 반환한다. */
    @Transactional(readOnly = true)
    public RequirementVersion assertVersionBelongsToProject(
            UUID versionId,
            UUID projectId,
            UUID userId
    ) {
        ProjectAccessContext access = projectService
                .getAccessContext(projectId, userId);
        RequirementVersion version = versionRepository
                .findById(versionId)
                .orElseThrow(() -> notFound(
                        "요구사항 버전을 찾을 수 없습니다."
                ));
        Requirement requirement = findRequirement(
                version.requirementId()
        );

        if (!requirement.getProjectId().equals(projectId) ||
                !requirement.getOrganizationId().equals(
                        access.organizationId()
                )) {
            throw notFound("요구사항 버전을 찾을 수 없습니다.");
        }

        return version;
    }

    private RequirementResponse toResponse(
            Requirement requirement,
            boolean customerView
    ) {
        RequirementVersion currentVersion = versionRepository
                .findCurrent(requirement.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "요구사항의 현재 버전을 찾을 수 없습니다."
                ));

        return RequirementResponse.from(
                requirement,
                currentVersion,
                customerView
        );
    }

    private Requirement findRequirement(UUID requirementId) {
        return requirementRepository.findById(requirementId)
                .orElseThrow(() -> notFound(
                        "요구사항을 찾을 수 없습니다."
                ));
    }

    private void assertCustomerCanSee(
            Requirement requirement,
            boolean customer,
            UUID userId
    ) {
        if (customer &&
                !requirement.isCustomerVisible() &&
                !userId.equals(requirement.getRequestedBy())) {
            throw notFound("요구사항을 찾을 수 없습니다.");
        }
    }

    private void assertCanChangeStatus(
            Requirement requirement,
            ProjectAccessContext access,
            RequirementStatusUpdateRequest request,
            UUID userId
    ) {
        if (WORKFLOW_MANAGERS.contains(access.memberRole())) {
            return;
        }

        if (request.customerVisible() != null) {
            throw new AccessDeniedException(
                    "요구사항 공개 범위 변경 권한이 없습니다."
            );
        }

        RequirementStatus current = requirement.getStatus();
        RequirementStatus next = request.status();

        if (access.hasRole("CUSTOMER") &&
                userId.equals(requirement.getRequestedBy()) &&
                (
                        current == RequirementStatus.DRAFT &&
                                next == RequirementStatus.SUBMITTED ||
                        Set.of(
                                RequirementStatus.DRAFT,
                                RequirementStatus.SUBMITTED
                        ).contains(current) &&
                                next == RequirementStatus.CANCELLED
                )) {
            return;
        }

        if (access.hasRole("DEVELOPER") &&
                current == RequirementStatus.ANALYZING &&
                next == RequirementStatus.REVIEWING) {
            return;
        }

        throw new AccessDeniedException(
                "요구사항 상태 변경 권한이 없습니다."
        );
    }

    /** 현재 상태에서 다음 상태로 이동 가능한지 검사한다; 결과 대신 실패 시 예외를 던진다. */
    private void assertTransitionAllowed(
            RequirementStatus current,
            RequirementStatus next
    ) {
        if (!ALLOWED_TRANSITIONS
                .getOrDefault(current, Set.of())
                .contains(next)) {
            throw conflict(
                    "허용되지 않은 요구사항 상태 전이입니다: " +
                            current + " -> " + next
            );
        }
    }

    private void assertRole(
            ProjectAccessContext access,
            Set<String> allowedRoles,
            String message
    ) {
        if (!allowedRoles.contains(access.memberRole())) {
            throw new AccessDeniedException(message);
        }
    }

    private RequirementSourceType sourceTypeFor(
            ProjectAccessContext access,
            RequirementSourceType requestedSourceType
    ) {
        if (access.hasRole("CUSTOMER")) {
            if (requestedSourceType != null &&
                    requestedSourceType !=
                            RequirementSourceType.CUSTOMER) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "고객 요구사항의 sourceType은 CUSTOMER여야 합니다."
                );
            }

            return RequirementSourceType.CUSTOMER;
        }

        return requestedSourceType == null
                ? RequirementSourceType.PM
                : requestedSourceType;
    }

    private RequirementVersionCreateRequest initialVersionRequest(
            RequirementCreateRequest request
    ) {
        return new RequirementVersionCreateRequest(
                request.title(),
                request.originalContent(),
                request.refinedContent(),
                request.acceptanceCriteria(),
                request.attachmentsMetadata(),
                request.changeNote()
        );
    }

    private static Map<RequirementStatus, Set<RequirementStatus>>
    transitions() {
        Map<RequirementStatus, Set<RequirementStatus>> transitions =
                new EnumMap<>(RequirementStatus.class);

        transitions.put(
                RequirementStatus.DRAFT,
                Set.of(
                        RequirementStatus.SUBMITTED,
                        RequirementStatus.CANCELLED
                )
        );
        transitions.put(
                RequirementStatus.SUBMITTED,
                Set.of(
                        RequirementStatus.REFINING,
                        RequirementStatus.REJECTED,
                        RequirementStatus.CANCELLED
                )
        );
        transitions.put(
                RequirementStatus.REFINING,
                Set.of(
                        RequirementStatus.READY,
                        RequirementStatus.REJECTED,
                        RequirementStatus.CANCELLED
                )
        );
        transitions.put(
                RequirementStatus.READY,
                Set.of(
                        RequirementStatus.ANALYZING,
                        RequirementStatus.REFINING,
                        RequirementStatus.CANCELLED
                )
        );
        transitions.put(
                RequirementStatus.ANALYZING,
                Set.of(
                        RequirementStatus.REVIEWING,
                        RequirementStatus.CANCELLED
                )
        );
        transitions.put(
                RequirementStatus.REVIEWING,
                Set.of(
                        RequirementStatus.REFINING,
                        RequirementStatus.ESTIMATING,
                        RequirementStatus.REJECTED,
                        RequirementStatus.CANCELLED
                )
        );
        transitions.put(
                RequirementStatus.ESTIMATING,
                Set.of(
                        RequirementStatus.REVIEWING,
                        RequirementStatus.APPROVED,
                        RequirementStatus.REJECTED,
                        RequirementStatus.CANCELLED
                )
        );
        transitions.put(
                RequirementStatus.REJECTED,
                Set.of(
                        RequirementStatus.REFINING,
                        RequirementStatus.CANCELLED
                )
        );

        return Map.copyOf(transitions);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                message
        );
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                message
        );
    }
}
