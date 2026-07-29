package com.doenaai.project.connection.repository;

import com.doenaai.project.connection.entity.ProjectRepositoryConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProjectRepositoryConnectionRepository
        extends JpaRepository<ProjectRepositoryConnection, UUID> {

    Optional<ProjectRepositoryConnection> findByProjectId(
            UUID projectId
    );
}
