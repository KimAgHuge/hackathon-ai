package com.doenaai.organization.service;

import com.doenaai.organization.dto.OrganizationCreateRequest;
import com.doenaai.organization.entity.Organization;
import com.doenaai.organization.repository.OrganizationMemberRepository;
import com.doenaai.organization.repository.OrganizationRepository;
import com.doenaai.user.repository.ProfileRepository;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(OrganizationServiceTransactionTest.Config.class)
class OrganizationServiceTransactionTest {

    @org.springframework.beans.factory.annotation.Autowired
    private OrganizationService organizationService;

    @org.springframework.beans.factory.annotation.Autowired
    private OrganizationRepository organizationRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private OrganizationMemberRepository organizationMemberRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private RecordingTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        reset(
                organizationRepository,
                organizationMemberRepository
        );
        transactionManager.reset();
    }

    @Test
    void createOrganizationRollsBackWhenOwnerSaveFails() {
        UUID organizationId = UUID.randomUUID();
        Organization organization = new Organization(
                "Acme",
                "acme",
                null,
                UUID.randomUUID()
        );

        ReflectionTestUtils.setField(
                organization,
                "id",
                organizationId
        );

        when(organizationRepository.existsBySlug("acme"))
                .thenReturn(false);
        when(organizationRepository.saveAndFlush(any()))
                .thenReturn(organization);
        doThrow(new IllegalStateException("owner save failed"))
                .when(organizationMemberRepository)
                .saveAndFlush(any());

        assertThatThrownBy(() ->
                organizationService.createOrganization(
                        new OrganizationCreateRequest(
                                "Acme",
                                "acme",
                                null
                        ),
                        UUID.randomUUID()
                )
        ).isInstanceOf(IllegalStateException.class);

        assertThat(transactionManager.rolledBack).isTrue();
        assertThat(transactionManager.committed).isFalse();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Config {

        @Bean
        OrganizationRepository organizationRepository() {
            return mock(OrganizationRepository.class);
        }

        @Bean
        OrganizationMemberRepository organizationMemberRepository() {
            return mock(OrganizationMemberRepository.class);
        }

        @Bean
        ProfileRepository profileRepository() {
            return mock(ProfileRepository.class);
        }

        @Bean
        RecordingTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }

        @Bean
        OrganizationService organizationService(
                OrganizationRepository organizationRepository,
                OrganizationMemberRepository organizationMemberRepository,
                ProfileRepository profileRepository
        ) {
            return new OrganizationService(
                    organizationRepository,
                    organizationMemberRepository,
                    profileRepository
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
        public void commit(
                TransactionStatus status
        ) {
            committed = true;
        }

        @Override
        public void rollback(
                TransactionStatus status
        ) {
            rolledBack = true;
        }

        void reset() {
            committed = false;
            rolledBack = false;
        }
    }
}
