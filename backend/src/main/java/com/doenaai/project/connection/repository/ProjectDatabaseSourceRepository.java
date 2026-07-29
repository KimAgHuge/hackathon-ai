package com.doenaai.project.connection.repository;

import com.doenaai.project.connection.entity.ProjectDatabaseSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProjectDatabaseSourceRepository
        extends JpaRepository<ProjectDatabaseSource, UUID> {

    Optional<ProjectDatabaseSource>
    findByProjectIdAndPrimaryConnectionTrue(
            UUID projectId
    );
}
