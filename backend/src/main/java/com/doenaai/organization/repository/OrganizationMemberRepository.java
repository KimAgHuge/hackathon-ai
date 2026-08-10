package com.doenaai.organization.repository;

import com.doenaai.organization.entity.OrganizationMember;
import com.doenaai.organization.entity.OrganizationMemberId;
import com.doenaai.organization.entity.OrganizationMemberRole;
import com.doenaai.organization.entity.OrganizationMemberStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationMemberRepository
        extends JpaRepository<OrganizationMember, OrganizationMemberId> {

    Optional<OrganizationMember>
    findByOrganizationIdAndUserId(
            UUID organizationId,
            UUID userId
    );

    Optional<OrganizationMember>
    findByOrganizationIdAndUserIdAndStatus(
            UUID organizationId,
            UUID userId,
            OrganizationMemberStatus status
    );

    List<OrganizationMember>
    findAllByUserIdAndStatus(
            UUID userId,
            OrganizationMemberStatus status
    );

    List<OrganizationMember>
    findAllByOrganizationIdOrderByJoinedAtAsc(
            UUID organizationId
    );

    long countByOrganizationIdAndMemberRoleAndStatus(
            UUID organizationId,
            OrganizationMemberRole memberRole,
            OrganizationMemberStatus status
    );
}
