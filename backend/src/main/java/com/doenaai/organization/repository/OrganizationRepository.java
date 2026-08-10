package com.doenaai.organization.repository;

import com.doenaai.organization.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OrganizationRepository
        extends JpaRepository<Organization, UUID> {

    boolean existsBySlug(String slug);

    List<Organization> findAllByIdInOrderByCreatedAtDesc(
            Collection<UUID> organizationIds
    );
}
