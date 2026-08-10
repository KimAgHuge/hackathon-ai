package com.doenaai.requirement.repository;

import com.doenaai.requirement.entity.Requirement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RequirementRepository
        extends JpaRepository<Requirement, UUID> {

    List<Requirement> findAllByProjectIdOrderByCreatedAtDesc(
            UUID projectId
    );

    @Query("""
            select r
            from Requirement r
            where r.projectId = :projectId
              and (
                    r.customerVisible = true
                    or r.requestedBy = :userId
              )
            order by r.createdAt desc
            """)
    List<Requirement> findAllVisibleToCustomer(
            @Param("projectId") UUID projectId,
            @Param("userId") UUID userId
    );
}
