package com.doenaai.organization.service;

import com.doenaai.organization.dto.OrganizationCreateRequest;
import com.doenaai.organization.dto.OrganizationMemberCreateRequest;
import com.doenaai.organization.dto.OrganizationMemberUpdateRequest;
import com.doenaai.organization.entity.Organization;
import com.doenaai.organization.entity.OrganizationMember;
import com.doenaai.organization.entity.OrganizationMemberRole;
import com.doenaai.organization.entity.OrganizationMemberStatus;
import com.doenaai.organization.repository.OrganizationMemberRepository;
import com.doenaai.organization.repository.OrganizationRepository;
import com.doenaai.user.entity.Profile;
import com.doenaai.user.repository.ProfileRepository;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrganizationServiceTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private OrganizationMemberRepository organizationMemberRepository;

    @Mock
    private ProfileRepository profileRepository;

    private OrganizationService organizationService;

    @BeforeEach
    void setUp() {
        organizationService = new OrganizationService(
                organizationRepository,
                organizationMemberRepository,
                profileRepository
        );
    }

    @Test
    void createOrganizationAddsCreatorAsOwner() {
        UUID organizationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Organization savedOrganization = organization(organizationId);

        when(organizationRepository.existsBySlug("acme"))
                .thenReturn(false);
        when(organizationRepository.saveAndFlush(any()))
                .thenReturn(savedOrganization);
        when(organizationMemberRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = organizationService.createOrganization(
                new OrganizationCreateRequest(
                        "Acme",
                        "acme",
                        "테스트 조직"
                ),
                userId
        );

        assertThat(response.id()).isEqualTo(organizationId);
        assertThat(response.memberRole())
                .isEqualTo(OrganizationMemberRole.OWNER);

        ArgumentCaptor<OrganizationMember> memberCaptor =
                ArgumentCaptor.forClass(OrganizationMember.class);

        verify(organizationMemberRepository)
                .saveAndFlush(memberCaptor.capture());

        OrganizationMember owner = memberCaptor.getValue();

        assertThat(owner.getOrganizationId())
                .isEqualTo(organizationId);
        assertThat(owner.getUserId()).isEqualTo(userId);
        assertThat(owner.getMemberRole())
                .isEqualTo(OrganizationMemberRole.OWNER);
        assertThat(owner.getStatus())
                .isEqualTo(OrganizationMemberStatus.ACTIVE);

        InOrder saveOrder = inOrder(
                organizationRepository,
                organizationMemberRepository
        );

        saveOrder.verify(organizationRepository)
                .saveAndFlush(any());
        saveOrder.verify(organizationMemberRepository)
                .saveAndFlush(any());
    }

    @Test
    void createOrganizationRejectsDuplicateSlug() {
        UUID userId = UUID.randomUUID();

        when(organizationRepository.existsBySlug("acme"))
                .thenReturn(true);

        assertThatThrownBy(() ->
                organizationService.createOrganization(
                        new OrganizationCreateRequest(
                                "Acme",
                                "acme",
                                null
                        ),
                        userId
                )
        )
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception)
                                .getStatusCode()
                ).isEqualTo(HttpStatus.CONFLICT));

        verify(organizationRepository, never())
                .saveAndFlush(any());
        verify(organizationMemberRepository, never())
                .saveAndFlush(any());
    }

    @Test
    void getOrganizationsReturnsOnlyActiveMemberships() {
        UUID userId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        OrganizationMember membership = member(
                organizationId,
                userId,
                OrganizationMemberRole.MEMBER,
                OrganizationMemberStatus.ACTIVE
        );

        when(organizationMemberRepository
                .findAllByUserIdAndStatus(
                        userId,
                        OrganizationMemberStatus.ACTIVE
                ))
                .thenReturn(List.of(membership));
        when(organizationRepository
                .findAllByIdInOrderByCreatedAtDesc(any()))
                .thenReturn(List.of(organization(organizationId)));

        var result = organizationService.getOrganizations(userId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().id())
                .isEqualTo(organizationId);
        assertThat(result.getFirst().memberRole())
                .isEqualTo(OrganizationMemberRole.MEMBER);
    }

    @Test
    void getOrganizationHidesOrganizationFromNonMember() {
        UUID organizationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(organizationMemberRepository
                .findByOrganizationIdAndUserIdAndStatus(
                        organizationId,
                        userId,
                        OrganizationMemberStatus.ACTIVE
                ))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                organizationService.getOrganization(
                        organizationId,
                        userId
                )
        )
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception)
                                .getStatusCode()
                ).isEqualTo(HttpStatus.NOT_FOUND));

        verify(organizationRepository, never()).findById(any());
    }

    @Test
    void memberCannotManageOrganizationMembers() {
        UUID organizationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        OrganizationMember actor = member(
                organizationId,
                actorId,
                OrganizationMemberRole.MEMBER,
                OrganizationMemberStatus.ACTIVE
        );

        stubActiveMembership(actor);
        when(organizationRepository.findById(organizationId))
                .thenReturn(Optional.of(organization(organizationId)));

        assertThatThrownBy(() ->
                organizationService.addMember(
                        organizationId,
                        new OrganizationMemberCreateRequest(
                                UUID.randomUUID(),
                                OrganizationMemberRole.MEMBER
                        ),
                        actorId
                )
        ).isInstanceOf(AccessDeniedException.class);

        verify(profileRepository, never()).findById(any());
        verify(organizationMemberRepository, never())
                .saveAndFlush(any());
    }

    @Test
    void ownerCanAddMember() {
        UUID organizationId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();
        OrganizationMember owner = member(
                organizationId,
                ownerId,
                OrganizationMemberRole.OWNER,
                OrganizationMemberStatus.ACTIVE
        );
        Profile targetProfile = profile(
                targetUserId,
                "대상 사용자"
        );

        stubActiveMembership(owner);
        when(organizationRepository.findById(organizationId))
                .thenReturn(Optional.of(organization(organizationId)));
        when(profileRepository.findById(targetUserId))
                .thenReturn(Optional.of(targetProfile));
        when(organizationMemberRepository
                .findByOrganizationIdAndUserId(
                        organizationId,
                        targetUserId
                ))
                .thenReturn(Optional.empty());
        when(organizationMemberRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = organizationService.addMember(
                organizationId,
                new OrganizationMemberCreateRequest(
                        targetUserId,
                        OrganizationMemberRole.ADMIN
                ),
                ownerId
        );

        assertThat(response.organizationId())
                .isEqualTo(organizationId);
        assertThat(response.userId()).isEqualTo(targetUserId);
        assertThat(response.displayName()).isEqualTo("대상 사용자");
        assertThat(response.memberRole())
                .isEqualTo(OrganizationMemberRole.ADMIN);
        assertThat(response.status())
                .isEqualTo(OrganizationMemberStatus.ACTIVE);
    }

    @Test
    void adminCanUpdateMember() {
        UUID organizationId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();
        OrganizationMember admin = member(
                organizationId,
                adminId,
                OrganizationMemberRole.ADMIN,
                OrganizationMemberStatus.ACTIVE
        );
        OrganizationMember target = member(
                organizationId,
                targetUserId,
                OrganizationMemberRole.MEMBER,
                OrganizationMemberStatus.ACTIVE
        );

        stubActiveMembership(admin);
        when(organizationRepository.findById(organizationId))
                .thenReturn(Optional.of(organization(organizationId)));
        when(organizationMemberRepository
                .findByOrganizationIdAndUserId(
                        organizationId,
                        targetUserId
                ))
                .thenReturn(Optional.of(target));
        when(organizationMemberRepository.saveAndFlush(target))
                .thenReturn(target);
        when(profileRepository.findById(targetUserId))
                .thenReturn(Optional.empty());

        var response = organizationService.updateMember(
                organizationId,
                targetUserId,
                new OrganizationMemberUpdateRequest(
                        OrganizationMemberRole.ADMIN,
                        OrganizationMemberStatus.ACTIVE
                ),
                adminId
        );

        assertThat(response.memberRole())
                .isEqualTo(OrganizationMemberRole.ADMIN);
        assertThat(response.status())
                .isEqualTo(OrganizationMemberStatus.ACTIVE);
        verify(organizationMemberRepository).saveAndFlush(target);
    }

    @Test
    void inactiveMemberCannotReadOrganizationMembers() {
        UUID organizationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(organizationMemberRepository
                .findByOrganizationIdAndUserIdAndStatus(
                        organizationId,
                        userId,
                        OrganizationMemberStatus.ACTIVE
                ))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                organizationService.getMembers(
                        organizationId,
                        userId
                )
        )
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception)
                                .getStatusCode()
                ).isEqualTo(HttpStatus.NOT_FOUND));

        verify(organizationMemberRepository, never())
                .findAllByOrganizationIdOrderByJoinedAtAsc(any());
    }

    @Test
    void cannotDeactivateLastActiveOwner() {
        UUID organizationId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        OrganizationMember owner = member(
                organizationId,
                ownerId,
                OrganizationMemberRole.OWNER,
                OrganizationMemberStatus.ACTIVE
        );

        stubActiveMembership(owner);
        when(organizationRepository.findById(organizationId))
                .thenReturn(Optional.of(organization(organizationId)));
        when(organizationMemberRepository
                .findByOrganizationIdAndUserId(
                        organizationId,
                        ownerId
                ))
                .thenReturn(Optional.of(owner));
        when(organizationMemberRepository
                .countByOrganizationIdAndMemberRoleAndStatus(
                        organizationId,
                        OrganizationMemberRole.OWNER,
                        OrganizationMemberStatus.ACTIVE
                ))
                .thenReturn(1L);

        assertThatThrownBy(() ->
                organizationService.updateMember(
                        organizationId,
                        ownerId,
                        new OrganizationMemberUpdateRequest(
                                OrganizationMemberRole.OWNER,
                                OrganizationMemberStatus.INACTIVE
                        ),
                        ownerId
                )
        )
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception)
                                .getStatusCode()
                ).isEqualTo(HttpStatus.CONFLICT));

        verify(organizationMemberRepository, never())
                .saveAndFlush(any());
    }

    private void stubActiveMembership(
            OrganizationMember membership
    ) {
        when(organizationMemberRepository
                .findByOrganizationIdAndUserIdAndStatus(
                        membership.getOrganizationId(),
                        membership.getUserId(),
                        OrganizationMemberStatus.ACTIVE
                ))
                .thenReturn(Optional.of(membership));
    }

    private Organization organization(
            UUID organizationId
    ) {
        Organization organization = new Organization(
                "Acme",
                "acme",
                "테스트 조직",
                UUID.randomUUID()
        );

        ReflectionTestUtils.setField(
                organization,
                "id",
                organizationId
        );

        return organization;
    }

    private OrganizationMember member(
            UUID organizationId,
            UUID userId,
            OrganizationMemberRole memberRole,
            OrganizationMemberStatus status
    ) {
        OrganizationMember member = new OrganizationMember(
                organizationId,
                userId,
                memberRole,
                UUID.randomUUID()
        );

        member.update(
                memberRole,
                status,
                UUID.randomUUID()
        );

        return member;
    }

    private Profile profile(
            UUID userId,
            String displayName
    ) {
        Profile profile = org.mockito.Mockito.mock(Profile.class);

        when(profile.getDisplayName()).thenReturn(displayName);

        return profile;
    }
}
