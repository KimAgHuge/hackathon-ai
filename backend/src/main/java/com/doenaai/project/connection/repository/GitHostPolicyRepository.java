package com.doenaai.project.connection.repository;

import com.doenaai.project.connection.entity.GitHostPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface GitHostPolicyRepository
        extends JpaRepository<GitHostPolicy, UUID> {

    Optional<GitHostPolicy> findByHostIgnoreCaseAndEnabledTrue(
            String host
    );
}
