package com.doenaai.project.repository;

import com.doenaai.project.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ProjectRepository
        extends JpaRepository<Project, UUID> {

    List<Project> findAllByOrderByCreatedAtDesc();

    @Query(
            value = """
                    select p.*
                    from public.projects p
                    join public.project_members pm
                      on pm.project_id = p.id
                    where pm.user_id = :userId
                    order by p.created_at desc
                    """,
            nativeQuery = true
    )
    List<Project> findAllByMemberUserId(
            @Param("userId") UUID userId
    );
}