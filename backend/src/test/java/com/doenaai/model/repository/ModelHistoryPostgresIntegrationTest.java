package com.doenaai.model.repository;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** 비어 있는 폐기용 로컬 DB에서만 멱등 저장과 배포 트랜잭션을 검사한다. */
@EnabledIfEnvironmentVariable(named = "DOENAAI_MODEL_TEST_POSTGRES_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelHistoryPostgresIntegrationTest {
    private final List<String> tables = List.of("model_deployments", "model_versions", "ai_models");
    private final UUID actor = UUID.randomUUID();
    private final String server = "f".repeat(64);
    private JdbcTemplate jdbc;
    private ModelHistoryRepository repository;
    private TransactionTemplate transactions;
    private boolean ownsTables;

    @BeforeAll void createFixture() {
        String url = System.getenv("DOENAAI_MODEL_TEST_POSTGRES_URL");
        var match = java.util.regex.Pattern.compile("^jdbc:postgresql://127\\.0\\.0\\.1:([1-9][0-9]{0,4})/doenaai_model_catalog_test$")
                .matcher(url == null ? "" : url);
        if (!match.matches() || Integer.parseInt(match.group(1)) > 65535) throw new IllegalArgumentException("폐기용 로컬 모델 관리 시험 DB 주소만 사용할 수 있습니다.");
        var source = new DriverManagerDataSource(url, "postgres", "doenaai-model-test");
        var properties = new Properties(); properties.setProperty("connectTimeout", "5"); properties.setProperty("socketTimeout", "20");
        source.setConnectionProperties(properties);
        jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("doenaai_model_catalog_test");
        var manager = new DataSourceTransactionManager(source); transactions = new TransactionTemplate(manager);
        transactions.executeWithoutResult(status -> {
            for (String table : tables) assertThat(jdbc.queryForObject("select to_regclass(?) is null", Boolean.class, "public." + table))
                    .as("기존 테이블이 있으면 시험을 거부합니다.").isTrue();
            jdbc.execute("""
                    create table public.ai_models (
                        id uuid primary key, name varchar(200) not null unique, description text, provider varchar(100),
                        base_model_ref varchar(500), status varchar(20) not null default 'ACTIVE', created_by uuid,
                        created_at timestamptz not null default now())
                    """);
            jdbc.execute("""
                    create table public.model_versions (
                        id uuid primary key, model_id uuid not null references public.ai_models(id), version varchar(100) not null,
                        artifact_type varchar(30) not null, status varchar(20) not null, base_model_ref varchar(500),
                        storage_provider varchar(30), artifact_uri text, checksum varchar(256), metadata jsonb not null,
                        created_by uuid, created_at timestamptz not null default now(), unique(model_id,version))
                    """);
            jdbc.execute("""
                    create table public.model_deployments (
                        id uuid primary key, model_version_id uuid not null references public.model_versions(id),
                        server_name varchar(200) not null, deployment_role varchar(30) not null,
                        status varchar(20) not null check(status in ('ACTIVE','INACTIVE','FAILED')), deployed_by uuid,
                        deployed_at timestamptz, undeployed_at timestamptz, metadata jsonb not null,
                        created_at timestamptz not null default now(), updated_at timestamptz not null default now())
                    """);
            jdbc.execute("create unique index history_fixture_active on public.model_deployments(server_name,deployment_role) where status='ACTIVE'");
        });
        ownsTables = true;
        var factory = new ProxyFactory(new ModelHistoryRepository(jdbc, JsonMapper.builder().build()));
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        repository = (ModelHistoryRepository) factory.getProxy();
    }

    @BeforeEach void clearFixture() {
        assertThat(ownsTables).isTrue();
        transactions.executeWithoutResult(status -> { for (String table : tables) jdbc.update("delete from public." + table); });
    }

    @AfterAll void removeFixture() {
        if (ownsTables) transactions.executeWithoutResult(status -> { for (String table : tables) jdbc.execute("drop table public." + table); });
        ownsTables = false;
    }

    @Test void repeatedRegistrationPreservesOneVersionAndFirstObservation() {
        UUID first = repository.registration(version("a", "2026-10-22T05:00:00Z"));
        UUID repeated = repository.registration(version("a", "2026-10-22T06:00:00Z"));
        assertThat(repeated).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from public.model_versions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select metadata->>'registeredObservedAt' from public.model_versions", String.class)).isEqualTo("2026-10-22T05:00:00Z");
    }

    @Test void replayAndOutOfOrderBackfillKeepOnlyTheLatestConfirmedRevisionActive() {
        UUID version = repository.registration(version("a", "2026-10-22T05:00:00Z"));
        var newest = deployment(UUID.randomUUID(), 5);
        repository.deployment(version, null, newest);
        repository.deployment(version, null, deployment(UUID.randomUUID(), 2));
        repository.deployment(version, null, newest);
        assertThat(jdbc.queryForObject("select count(*) from public.model_deployments", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select metadata->>'selectionRevision' from public.model_deployments where status='ACTIVE'", String.class)).isEqualTo("5");
    }

    @Test void failedNewDeploymentRollsBackPreviousActiveDeactivation() {
        UUID version = repository.registration(version("a", "2026-10-22T05:00:00Z"));
        repository.deployment(version, null, deployment(UUID.randomUUID(), 1));
        assertThatThrownBy(() -> repository.deployment(UUID.randomUUID(), null, deployment(UUID.randomUUID(), 2)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from public.model_deployments where status='ACTIVE'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select metadata->>'selectionRevision' from public.model_deployments where status='ACTIVE'", String.class)).isEqualTo("1");
    }

    @Test void conflictingVersionEvidenceCannotOverwriteTheOriginalChecksum() {
        var original = version("a", "2026-10-22T05:00:00Z"); repository.registration(original);
        var changed = new ModelHistoryRepository.Version(original.model(), original.version(), "BASE", original.baseModel(), "b".repeat(64), actor, original.metadata());
        assertThatThrownBy(() -> repository.registration(changed)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select checksum from public.model_versions", String.class)).isEqualTo("a".repeat(64));
    }

    @Test void sameWeightsCannotReplacePreviouslyRecordedManifestEvidence() {
        var base = version("a", "2026-10-22T05:00:00Z");
        var original = new ModelHistoryRepository.Version(base.model(), base.version(), "MERGED", base.baseModel(), base.checksum(), actor,
                Map.of("engineDigest", "a".repeat(64), "manifestSha256", "b".repeat(64), "registeredObservedAt", "2026-10-22T05:00:00Z"));
        repository.registration(original);
        var changed = new ModelHistoryRepository.Version(base.model(), base.version(), "MERGED", base.baseModel(), base.checksum(), actor,
                Map.of("engineDigest", "a".repeat(64), "manifestSha256", "c".repeat(64), "registeredObservedAt", "2026-10-22T06:00:00Z"));
        assertThatThrownBy(() -> repository.registration(changed)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select metadata->>'manifestSha256' from public.model_versions", String.class)).isEqualTo("b".repeat(64));
    }

    @Test void verifiedObservationRepairsOldFlagsAndPreservesTheFirstObservationTime() {
        var base = version("a", "2026-10-22T05:00:00Z"); repository.registration(base);
        jdbc.update("update public.model_versions set metadata = metadata || '{\"registrationVerified\":false}'::jsonb");
        var verified = new ModelHistoryRepository.Version(base.model(), base.version(), base.artifactType(), base.baseModel(), base.checksum(), actor,
                Map.of("engineDigest", "a".repeat(64), "registrationVerified", true, "historySource", "RUNTIME_SELECTION", "registeredObservedAt", "2026-10-22T06:00:00Z"));
        repository.registration(verified);
        assertThat(jdbc.queryForObject("select metadata->>'registrationVerified' from public.model_versions", String.class)).isEqualTo("true");
        assertThat(jdbc.queryForObject("select metadata->>'registeredObservedAt' from public.model_versions", String.class)).isEqualTo("2026-10-22T05:00:00Z");
    }

    private ModelHistoryRepository.Version version(String hash, String observation) {
        return new ModelHistoryRepository.Version("qwen3.8:27b", "installed-fixture", "BASE", "qwen3.8:27b", hash.repeat(64), actor,
                Map.of("engineDigest", hash.repeat(64), "historySource", "RUNTIME_SELECTION", "registeredObservedAt", observation));
    }

    private ModelHistoryRepository.Deployment deployment(UUID id, long revision) {
        return new ModelHistoryRepository.Deployment(id, server, revision, actor, Instant.parse("2026-10-22T06:00:00Z"),
                Map.of("historySource", "MODEL_EXECUTION", "selectionRevision", revision, "operationId", id.toString()));
    }
}
