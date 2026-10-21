package com.doenaai.model.repository;

import com.doenaai.model.dto.ModelEvaluationRequest;
import com.doenaai.model.dto.ModelVersionCreateRequest;
import com.doenaai.model.service.ModelCatalogException;
import com.doenaai.model.service.ModelCatalogService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * 비어 있는 폐기용 로컬 DB에서만 선택해서 실행한다. 앱 DB와 기존 테이블은 사용하지 않는다.
 * DOENAAI_MODEL_TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:PORT/doenaai_model_catalog_test로 켠다.
 * 시험 계정은 postgres / doenaai-model-test다. 인증 테이블의 외래키와 행 접근 정책은 이 시험 범위가 아니다.
 */
@EnabledIfEnvironmentVariable(named = "DOENAAI_MODEL_TEST_POSTGRES_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelCatalogPostgresIntegrationTest {
    private final List<String> tables = List.of("model_evaluations", "model_deployments", "training_jobs", "model_versions", "ai_models");
    private JdbcTemplate jdbc;
    private ModelCatalogService service;
    private TransactionTemplate transactions;
    private boolean ownsTables;

    @BeforeAll void createFixture() {
        String url = System.getenv("DOENAAI_MODEL_TEST_POSTGRES_URL");
        var match = java.util.regex.Pattern.compile("^jdbc:postgresql://127\\.0\\.0\\.1:([1-9][0-9]{0,4})/doenaai_model_catalog_test$")
                .matcher(url == null ? "" : url);
        if (!match.matches() || Integer.parseInt(match.group(1)) > 65535) {
            throw new IllegalArgumentException("폐기용 로컬 모델 관리 시험 DB 주소만 사용할 수 있습니다.");
        }
        var source = new DriverManagerDataSource(url, "postgres", "doenaai-model-test");
        var properties = new Properties();
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "20");
        source.setConnectionProperties(properties);
        jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("doenaai_model_catalog_test");
        var manager = new DataSourceTransactionManager(source);
        transactions = new TransactionTemplate(manager);
        transactions.executeWithoutResult(status -> {
            for (String table : tables) {
                assertThat(jdbc.queryForObject("select to_regclass(?) is null", Boolean.class, "public." + table))
                        .as("기존 테이블이 있으면 시험을 거부합니다.").isTrue();
            }
            jdbc.execute("""
                    create table public.ai_models (
                        id uuid primary key, name varchar(200) not null unique, description text, provider varchar(100),
                        base_model_ref varchar(500), status varchar(20) not null default 'ACTIVE', created_by uuid,
                        created_at timestamptz not null default now())
                    """);
            jdbc.execute("""
                    create table public.model_versions (
                        id uuid primary key, model_id uuid not null references public.ai_models(id), version varchar(100) not null,
                        artifact_type varchar(30) not null check (artifact_type in ('BASE','LORA','MERGED','EXTERNAL')),
                        status varchar(20) not null default 'READY', base_model_ref varchar(500), storage_provider varchar(30),
                        artifact_uri text, adapter_uri text, checksum varchar(256), metadata jsonb not null default '{}'::jsonb,
                        created_by uuid, created_at timestamptz not null default now(), unique(model_id, version),
                        check (version <> 'reject-fixture'))
                    """);
            jdbc.execute("""
                    create table public.model_evaluations (
                        id uuid primary key, model_version_id uuid not null references public.model_versions(id),
                        evaluation_type varchar(30) not null default 'AUTOMATIC', status varchar(20) not null,
                        metrics jsonb not null default '{}'::jsonb, overall_score numeric(10,4), notes text, evaluated_by uuid,
                        evaluated_at timestamptz, created_at timestamptz not null default now())
                    """);
            jdbc.execute("""
                    create table public.training_jobs (
                        id uuid primary key, model_id uuid not null references public.ai_models(id), status varchar(20) not null,
                        progress integer not null default 0, worker_name varchar(200), created_at timestamptz not null default now(),
                        started_at timestamptz, completed_at timestamptz)
                    """);
            jdbc.execute("""
                    create table public.model_deployments (
                        id uuid primary key, model_version_id uuid not null references public.model_versions(id),
                        server_name varchar(200) not null, deployment_role varchar(30) not null, status varchar(20) not null,
                        deployed_at timestamptz, created_at timestamptz not null default now())
                    """);
        });
        ownsTables = true;
        var factory = new ProxyFactory(new ModelCatalogService(new ModelCatalogRepository(jdbc, JsonMapper.builder().build())));
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        service = (ModelCatalogService) factory.getProxy();
    }

    @BeforeEach void clearFixtures() {
        assertThat(ownsTables).isTrue();
        transactions.executeWithoutResult(status -> {
            for (String table : tables) jdbc.update("delete from public." + table);
        });
    }

    @AfterAll void removeFixtures() {
        if (!ownsTables) return;
        transactions.executeWithoutResult(status -> {
            for (String table : tables) jdbc.execute("drop table public." + table);
        });
        ownsTables = false;
    }

    @Test void savesAndReadsReadyVersionAndManualEvaluationWithoutPromotingStatus() {
        UUID actor = UUID.randomUUID();
        var registered = service.createVersion(request(null, "분석 모델", "v1"), actor);
        UUID versionId = (UUID) registered.get("id");
        assertThat(registered).containsEntry("status", "READY").containsEntry("datasetName", "검토 데이터")
                .containsEntry("artifactUri", "models/v1").containsEntry("notes", "등록 기록");
        var result = service.recordEvaluation(versionId,
                new ModelEvaluationRequest("PASSED", BigDecimal.valueOf(95), "수동 확인", Map.of("sampleCount", 3)), actor);
        assertThat(result.get("status")).isEqualTo("READY");
        assertThat(result.get("evaluations").toString()).contains("MANUAL", "PASSED", "sampleCount");
        assertThat(jdbc.queryForObject("select evaluated_by from public.model_evaluations", UUID.class)).isEqualTo(actor);
        assertThat(jdbc.queryForObject("select created_by from public.model_versions", UUID.class)).isEqualTo(actor);
        assertThat((List<?>) service.catalog().get("versions")).hasSize(1);
    }

    @Test void duplicateNamesAndVersionsReturnConflictAndPreserveTheOriginal() {
        var original = service.createVersion(request(null, "분석 모델", "v1"), UUID.randomUUID());
        UUID modelId = (UUID) original.get("modelId");
        for (var request : List.of(request(null, "분석 모델", "v2"), request(modelId, "바뀌면 안 되는 이름", "v1"))) {
            assertThatThrownBy(() -> service.createVersion(request, UUID.randomUUID()))
                    .isInstanceOfSatisfying(ModelCatalogException.class, error -> assertThat(error.status().value()).isEqualTo(409));
        }
        assertThat(jdbc.queryForObject("select count(*) from public.ai_models", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from public.model_versions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select name from public.ai_models", String.class)).isEqualTo("분석 모델");
    }

    @Test void secondInsertFailureActuallyRollsBackTheFirstInsert() {
        assertThatThrownBy(() -> service.createVersion(request(null, "되돌릴 모델", "reject-fixture"), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from public.ai_models", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from public.model_versions", Integer.class)).isZero();
    }

    @Test void databaseBackedListsReportActualOverflow() {
        jdbc.update("""
                insert into public.ai_models (id, name)
                select gen_random_uuid(), 'fixture-' || value from generate_series(1,101) value
                """);
        var catalog = service.catalog();
        assertThat((List<?>) catalog.get("models")).hasSize(100);
        assertThat(((Map<?, ?>) catalog.get("truncated")).get("models")).isEqualTo(true);
    }

    private static ModelVersionCreateRequest request(UUID modelId, String name, String version) {
        return new ModelVersionCreateRequest(modelId, name, "시험 설명", "MLX", "example/model", version, "LORA",
                "models/v1", "adapters/v1", "a".repeat(64), "검토 데이터", "등록 기록");
    }
}
