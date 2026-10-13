package com.doenaai.organization.service;

import com.doenaai.organization.dto.OrganizationCreateRequest;
import com.doenaai.organization.dto.OrganizationMemberCreateRequest;
import com.doenaai.organization.dto.OrganizationMemberResponse;
import com.doenaai.organization.dto.OrganizationMemberUpdateRequest;
import com.doenaai.organization.dto.OrganizationResponse;
import com.doenaai.organization.entity.Organization;
import com.doenaai.organization.entity.OrganizationMember;
import com.doenaai.organization.entity.OrganizationMemberRole;
import com.doenaai.organization.entity.OrganizationMemberStatus;
import com.doenaai.organization.repository.OrganizationMemberRepository;
import com.doenaai.organization.repository.OrganizationRepository;
import com.doenaai.user.entity.Profile;
import com.doenaai.user.repository.ProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrganizationService {

    private static final Set<OrganizationMemberRole> MANAGER_ROLES =
            Set.of(
                    OrganizationMemberRole.OWNER,
                    OrganizationMemberRole.ADMIN
            );

    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository
            organizationMemberRepository;
    private final ProfileRepository profileRepository;

    /** request의 조직을 만들고 userId를 OWNER로 등록한 응답을 반환한다. */
    @Transactional
    public OrganizationResponse createOrganization(
            OrganizationCreateRequest request,
            UUID userId
    ) {
        if (organizationRepository.existsBySlug(request.slug())) {
            throw conflict("이미 사용 중인 조직 slug입니다.");
        }

        Organization organization = new Organization(
                request.name(),
                request.slug(),
                request.description(),
                userId
        );

        Organization savedOrganization;

        try {
            savedOrganization =
                    organizationRepository.saveAndFlush(organization);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("이미 사용 중인 조직 slug입니다.");
        }

        OrganizationMember owner = new OrganizationMember(
                savedOrganization.getId(),
                userId,
                OrganizationMemberRole.OWNER,
                userId
        );

        organizationMemberRepository.saveAndFlush(owner);

        return OrganizationResponse.from(
                savedOrganization,
                OrganizationMemberRole.OWNER
        );
    }

    /** userId가 활성 구성원인 조직과 각 조직의 역할을 반환한다. */
    @Transactional(readOnly = true)
    public List<OrganizationResponse> getOrganizations(
            UUID userId
    ) {
        List<OrganizationMember> memberships =
                organizationMemberRepository
                        .findAllByUserIdAndStatus(
                                userId,
                                OrganizationMemberStatus.ACTIVE
                        );

        if (memberships.isEmpty()) {
            return List.of();
        }

        Map<UUID, OrganizationMemberRole> rolesByOrganization =
                memberships.stream()
                        .collect(Collectors.toMap(
                                OrganizationMember::getOrganizationId,
                                OrganizationMember::getMemberRole
                        ));

        return organizationRepository
                .findAllByIdInOrderByCreatedAtDesc(
                        rolesByOrganization.keySet()
                )
                .stream()
                .map(organization -> OrganizationResponse.from(
                        organization,
                        rolesByOrganization.get(organization.getId())
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public OrganizationResponse getOrganization(
            UUID organizationId,
            UUID userId
    ) {
        OrganizationMember membership =
                requireActiveMembership(organizationId, userId);
        Organization organization = findOrganization(organizationId);

        return OrganizationResponse.from(
                organization,
                membership.getMemberRole()
        );
    }

    /** organizationId에서 userId의 활성 소속을 확인한다; 미소속이면 예외를 던진다. */
    @Transactional(readOnly = true)
    public void assertActiveMember(
            UUID organizationId,
            UUID userId
    ) {
        requireActiveMembership(organizationId, userId);
        findOrganization(organizationId);
    }

    @Transactional(readOnly = true)
    public List<OrganizationMemberResponse> getMembers(
            UUID organizationId,
            UUID userId
    ) {
        requireActiveMembership(organizationId, userId);
        findOrganization(organizationId);

        List<OrganizationMember> members =
                organizationMemberRepository
                        .findAllByOrganizationIdOrderByJoinedAtAsc(
                                organizationId
                        );

        return toMemberResponses(members);
    }

    /** 관리 권한을 확인하고 request.userId를 조직 구성원으로 추가해 반환한다. */
    @Transactional
    public OrganizationMemberResponse addMember(
            UUID organizationId,
            OrganizationMemberCreateRequest request,
            UUID userId
    ) {
        assertCanManage(organizationId, userId);

        Profile profile = profileRepository
                .findById(request.userId())
                .orElseThrow(() -> notFound(
                        "추가할 사용자 프로필을 찾을 수 없습니다."
                ));

        if (organizationMemberRepository
                .findByOrganizationIdAndUserId(
                        organizationId,
                        request.userId()
                )
                .isPresent()) {
            throw conflict("이미 조직에 등록된 사용자입니다.");
        }

        OrganizationMember member = new OrganizationMember(
                organizationId,
                request.userId(),
                request.memberRole(),
                userId
        );

        try {
            organizationMemberRepository.saveAndFlush(member);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("이미 조직에 등록된 사용자입니다.");
        }

        return OrganizationMemberResponse.from(member, profile);
    }

    @Transactional
    public OrganizationMemberResponse updateMember(
            UUID organizationId,
            UUID targetUserId,
            OrganizationMemberUpdateRequest request,
            UUID userId
    ) {
        assertCanManage(organizationId, userId);

        OrganizationMember member = findMember(
                organizationId,
                targetUserId
        );

        ensureActiveOwnerRemains(
                member,
                request.memberRole(),
                request.status()
        );

        member.update(
                request.memberRole(),
                request.status(),
                userId
        );

        organizationMemberRepository.saveAndFlush(member);

        Profile profile = profileRepository
                .findById(targetUserId)
                .orElse(null);

        return OrganizationMemberResponse.from(member, profile);
    }

    @Transactional
    public void deleteMember(
            UUID organizationId,
            UUID targetUserId,
            UUID userId
    ) {
        assertCanManage(organizationId, userId);

        OrganizationMember member = findMember(
                organizationId,
                targetUserId
        );

        ensureActiveOwnerRemains(
                member,
                OrganizationMemberRole.MEMBER,
                OrganizationMemberStatus.INACTIVE
        );

        organizationMemberRepository.delete(member);
    }

    private Organization findOrganization(
            UUID organizationId
    ) {
        return organizationRepository
                .findById(organizationId)
                .orElseThrow(() -> notFound(
                        "조직을 찾을 수 없습니다."
                ));
    }

    private OrganizationMember findMember(
            UUID organizationId,
            UUID userId
    ) {
        return organizationMemberRepository
                .findByOrganizationIdAndUserId(
                        organizationId,
                        userId
                )
                .orElseThrow(() -> notFound(
                        "조직 구성원을 찾을 수 없습니다."
                ));
    }

    private OrganizationMember requireActiveMembership(
            UUID organizationId,
            UUID userId
    ) {
        return organizationMemberRepository
                .findByOrganizationIdAndUserIdAndStatus(
                        organizationId,
                        userId,
                        OrganizationMemberStatus.ACTIVE
                )
                .orElseThrow(() -> notFound(
                        "조직을 찾을 수 없습니다."
                ));
    }

    private void assertCanManage(
            UUID organizationId,
            UUID userId
    ) {
        OrganizationMember membership =
                requireActiveMembership(organizationId, userId);
        findOrganization(organizationId);

        if (!MANAGER_ROLES.contains(membership.getMemberRole())) {
            throw new AccessDeniedException(
                    "조직 구성원 관리 권한이 없습니다."
            );
        }
    }

    private void ensureActiveOwnerRemains(
            OrganizationMember member,
            OrganizationMemberRole nextRole,
            OrganizationMemberStatus nextStatus
    ) {
        boolean removesActiveOwner =
                member.getMemberRole() == OrganizationMemberRole.OWNER &&
                        member.getStatus() ==
                                OrganizationMemberStatus.ACTIVE &&
                        (nextRole != OrganizationMemberRole.OWNER ||
                                nextStatus !=
                                        OrganizationMemberStatus.ACTIVE);

        if (!removesActiveOwner) {
            return;
        }

        long activeOwnerCount = organizationMemberRepository
                .countByOrganizationIdAndMemberRoleAndStatus(
                        member.getOrganizationId(),
                        OrganizationMemberRole.OWNER,
                        OrganizationMemberStatus.ACTIVE
                );

        if (activeOwnerCount <= 1) {
            throw conflict(
                    "조직에는 한 명 이상의 활성 OWNER가 필요합니다."
            );
        }
    }

    private List<OrganizationMemberResponse> toMemberResponses(
            List<OrganizationMember> members
    ) {
        Map<UUID, Profile> profilesById = profileRepository
                .findAllById(
                        members.stream()
                                .map(OrganizationMember::getUserId)
                                .toList()
                )
                .stream()
                .collect(Collectors.toMap(
                        Profile::getId,
                        Function.identity()
                ));

        return members.stream()
                .map(member -> OrganizationMemberResponse.from(
                        member,
                        profilesById.get(member.getUserId())
                ))
                .toList();
    }

    private ResponseStatusException notFound(
            String message
    ) {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                message
        );
    }

    private ResponseStatusException conflict(
            String message
    ) {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                message
        );
    }
}
