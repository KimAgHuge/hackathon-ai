package com.doenaai.requirement.service;

import com.doenaai.project.dto.ProjectAccessContext;
import com.doenaai.project.service.ProjectService;
import com.doenaai.requirement.dto.RequirementCreateRequest;
import com.doenaai.requirement.entity.Requirement;
import com.doenaai.requirement.repository.RequirementRepository;
import com.doenaai.requirement.repository.RequirementVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(RequirementServiceTransactionTest.Config.class)
class RequirementServiceTransactionTest {

    @org.springframework.beans.factory.annotation.Autowired
    private RequirementService requirementService;

    @org.springframework.beans.factory.annotation.Autowired
    private RequirementRepository requirementRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private RequirementVersionRepository versionRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private ProjectService projectService;

    @org.springframework.beans.factory.annotation.Autowired
    private RecordingTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        reset(
                requirementRepository,
                versionRepository,
                projectService
        );
        transactionManager.reset();
    }

    @Test
    void initialVersionFailureRollsBackRequirementCreation() {
        UUID projectId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID requirementId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(projectService.getAccessContext(projectId, userId))
                .thenReturn(new ProjectAccessContext(
                        projectId,
                        organizationId,
                        "OWNER"
                ));
        when(requirementRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> {
                    Requirement requirement = invocation.getArgument(0);
                    ReflectionTestUtils.setField(
                            requirement,
                            "id",
                            requirementId
                    );
                    return requirement;
                });
        doThrow(new IllegalStateException("version save failed"))
                .when(versionRepository)
                .insert(
                        eq(requirementId),
                        eq(1),
                        any(),
                        eq(userId)
                );

        assertThatThrownBy(() ->
                requirementService.createRequirement(
                        projectId,
                        new RequirementCreateRequest(
                                "REQ-001",
                                "Login requirement",
                                null,
                                null,
                                null,
                                false,
                                "Users must log in.",
                                null,
                                List.of(),
                                List.of(),
                                null
                        ),
                        userId
                )
        ).isInstanceOf(IllegalStateException.class);

        assertThat(transactionManager.rolledBack).isTrue();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Config {

        @Bean
        RequirementRepository requirementRepository() {
            return mock(RequirementRepository.class);
        }

        @Bean
        RequirementVersionRepository versionRepository() {
            return mock(RequirementVersionRepository.class);
        }

        @Bean
        ProjectService projectService() {
            return mock(ProjectService.class);
        }

        @Bean
        RecordingTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }

        @Bean
        RequirementService requirementService(
                RequirementRepository requirementRepository,
                RequirementVersionRepository versionRepository,
                ProjectService projectService
        ) {
            return new RequirementService(
                    requirementRepository,
                    versionRepository,
                    projectService
            );
        }
    }

    static class RecordingTransactionManager
            implements PlatformTransactionManager {

        private boolean committed;
        private boolean rolledBack;

        @Override
        public TransactionStatus getTransaction(
                TransactionDefinition definition
        ) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            committed = true;
        }

        @Override
        public void rollback(TransactionStatus status) {
            rolledBack = true;
        }

        void reset() {
            committed = false;
            rolledBack = false;
        }
    }
}
