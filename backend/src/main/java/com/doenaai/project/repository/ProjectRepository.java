package com.doenaai.project.repository;

import com.doenaai.project.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ProjectRepository
        extends JpaRepository<Project, UUID> {

    @Query(
            value = """
                    select distinct p.*
                    from public.projects p
                    join public.organization_members om
                      on om.organization_id = p.organization_id
                    where om.user_id = :userId
                      and om.status = 'ACTIVE'
                    order by p.created_at desc
                    """,
            nativeQuery = true
    )
    List<Project> findAllByOrganizationMemberUserId(
            @Param("userId") UUID userId
    );

    @Query(
            value = """
                    select distinct p.*
                    from public.projects p
                    join public.project_members pm
                      on pm.project_id = p.id
                    join public.organization_members om
                      on om.organization_id = p.organization_id
                    where pm.user_id = :userId
                      and om.user_id = :userId
                      and om.status = 'ACTIVE'
                    order by p.created_at desc
                    """,
            nativeQuery = true
    )
    List<Project> findAllByProjectAndOrganizationMemberUserId(
            @Param("userId") UUID userId
    );
}
