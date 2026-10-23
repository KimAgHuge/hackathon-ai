package com.doenaai.model.repository;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** 폐기용 로컬 DB에서 전체 이력 검색과 제한된 페이지 조회를 검사한다. */
@EnabledIfEnvironmentVariable(named = "DOENAAI_MODEL_TEST_POSTGRES_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelOperationPostgresIntegrationTest {
    private JdbcTemplate jdbc;
    private ModelOperationRepository repository;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private boolean ownsTable;

    @BeforeAll void createFixture() {
        String url = System.getenv("DOENAAI_MODEL_TEST_POSTGRES_URL");
        var match = java.util.regex.Pattern.compile("^jdbc:postgresql://127\\.0\\.0\\.1:([1-9][0-9]{0,4})/doenaai_model_catalog_test$")
                .matcher(url == null ? "" : url);
        if (!match.matches() || Integer.parseInt(match.group(1)) > 65535)
            throw new IllegalArgumentException("폐기용 로컬 모델 관리 시험 DB 주소만 사용할 수 있습니다.");
        var source = new DriverManagerDataSource(url, "postgres", "doenaai-model-test");
        var properties = new Properties(); properties.setProperty("connectTimeout", "5"); properties.setProperty("socketTimeout", "20");
        source.setConnectionProperties(properties);
        jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("doenaai_model_catalog_test");
        assertThat(jdbc.queryForObject("select to_regclass('public.model_management_operations') is null", Boolean.class))
                .as("기존 테이블이 있으면 시험을 거부합니다.").isTrue();
        jdbc.execute("""
                create table public.model_management_operations (
                    id uuid primary key, action varchar(30) not null, status varchar(30) not null,
                    actor_id uuid not null, server_role varchar(30) not null, target_key varchar(64) not null,
                    request jsonb not null, result jsonb not null, before_model varchar(500), after_model varchar(500),
                    error_code varchar(60), created_at timestamptz not null, updated_at timestamptz not null)
                """);
        ownsTable = true;
        var factory = new ProxyFactory(new ModelOperationRepository(jdbc, mapper));
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        repository = (ModelOperationRepository) factory.getProxy();
    }

    @BeforeEach void clearFixture() {
        assertThat(ownsTable).isTrue();
        jdbc.update("delete from public.model_management_operations");
    }

    @AfterAll void removeFixture() {
        if (ownsTable) jdbc.execute("drop table public.model_management_operations");
        ownsTable = false;
    }

    @Test void paginationIncludesRecordsBeyondOneHundredAndClampsToTheLastPage() {
        for (int index = 1; index <= 137; index++) insert(index, "TRAINING_START", "SUCCEEDED", Map.of("model", "qwen3.8:27b"), Map.of(), null);
        var first = repository.page(0, 10, "", "");
        assertThat(first.totalCount()).isEqualTo(137);
        assertThat(first.operations()).hasSize(10);
        assertThat(first.operations().getFirst().get("id")).isEqualTo(new UUID(0, 137).toString());
        var last = repository.page(Integer.MAX_VALUE, 10, "", "");
        assertThat(last.page()).isEqualTo(13);
        assertThat(last.operations()).hasSize(7);
        assertThat(last.operations().getLast().get("id")).isEqualTo(new UUID(0, 1).toString());
        assertThat(repository.page(0, 10, new UUID(0, 1).toString(), "").operations()).hasSize(1);
    }

    @Test void koreanLabelsAndRawStatesUseTheSameStatusFilterForCountAndRows() {
        insert(1, "MODEL_ACTIVATE", "APPLIED", Map.of("model", "example:1b"), Map.of(), null);
        insert(2, "TRAINING_START", "SUCCEEDED", Map.of("model", "example:1b"), Map.of(), null);
        insert(3, "TRAINING_START", "FAILED", Map.of("model", "example:1b"), Map.of(), null);
        assertThat(repository.page(0, 10, "모델 전환", "").totalCount()).isEqualTo(1);
        assertThat(repository.page(0, 10, "완료", "SUCCEEDED").totalCount()).isEqualTo(1);
        assertThat(repository.page(0, 10, "학습", "FAILED").operations()).extracting(row -> row.get("status")).containsExactly("FAILED");
        assertThat(repository.page(0, 10, "FAILED", "FAILED").totalCount()).isEqualTo(1);
        assertThat(repository.page(0, 10, "학습", "APPLIED").totalCount()).isZero();
    }

    @Test void modelSearchIncludesNestedTargetsRequestsAndTransitionFields() {
        insert(1, "TRAINING_START", "SUCCEEDED", Map.of("model", "request-example:1b"),
                Map.of("target", Map.of("sourceModel", "source-example:1b", "baseModel", "org/Base-Example")), null);
        insert(2, "MODEL_ROLLBACK", "ROLLED_BACK", Map.of(), Map.of("model", "after-example:1b", "previousModel", "previous-example:1b"), null);
        jdbc.update("update public.model_management_operations set before_model='before-example:1b', after_model='selected-example:1b' where id=?", new UUID(0, 2));
        for (String query : List.of("request-example", "SOURCE-example", "base-example", "after-example", "previous-example", "before-example", "selected-example"))
            assertThat(repository.page(0, 10, query, "").totalCount()).as(query).isEqualTo(1);
    }

    @Test void searchTermsCanMatchSeparateFieldsInAnyOrder() {
        insert(1, "TRAINING_START", "SUCCEEDED", Map.of("model", "qwen3.8:27b"), Map.of(), null);
        insert(2, "TRAINING_START", "FAILED", Map.of("model", "gemma4:26b"), Map.of(), null);
        insert(3, "MODEL_PROBE", "FAILED", Map.of("model", "qwen3.8:27b"), Map.of(), null);
        assertThat(repository.page(0, 10, "qwen 학습", "").operations()).extracting(row -> row.get("id"))
                .containsExactly(new UUID(0, 1).toString());
        assertThat(repository.page(0, 10, "실패 학습", "").operations()).extracting(row -> row.get("id"))
                .containsExactly(new UUID(0, 2).toString());
        assertThat(repository.page(0, 10, "학습\tqwen", "SUCCEEDED").totalCount()).isEqualTo(1);
        assertThat(repository.page(0, 10, "qwen 실패 학습", "").totalCount()).isZero();
    }

    @Test void wildcardCharactersAreLiteralAndSqlLookingInputCannotBroadenTheSearch() {
        insert(1, "MODEL_PROBE", "FAILED", Map.of("model", "example:1b"), Map.of(), "ERR_%!");
        insert(2, "MODEL_PROBE", "FAILED", Map.of("model", "example:2b"), Map.of(), "ERR_OTHER");
        assertThat(repository.page(0, 10, "_%!", "").totalCount()).isEqualTo(1);
        assertThat(repository.page(0, 10, "' OR 1=1 --", "").totalCount()).isZero();
    }

    @Test void compatibilityFormsAndSelectedTrainingTargetsCanBeSearched() {
        insert(1, "TRAINING_START", "SUCCEEDED", Map.of("model", "qwen3.8:27b"),
                Map.of("target", Map.of("selectedModel", "doenaai-trained:web-selected", "sourceModel", "qwen3.8:27b")), null);
        insert(2, "MODEL_PROBE", "PASSED", Map.of("model", "Ｑｗｅｎ-example:1b"), Map.of(), null);
        assertThat(repository.page(0, 10, "ｑｗｅｎ", "").totalCount()).isEqualTo(2);
        assertThat(repository.page(0, 10, "web-selected 학습", "SUCCEEDED").operations())
                .extracting(row -> row.get("id")).containsExactly(new UUID(0, 1).toString());
    }

    @Test void privateFieldsCannotBeSearchedAndEmptyResultsReturnPageZero() {
        insert(1, "MODEL_PROBE", "PASSED", Map.of("model", "example:1b", "probeTokenHash", "private-probe-evidence"), Map.of(), null);
        var result = repository.page(100, 10, "private-probe-evidence", "");
        assertThat(result.totalCount()).isZero();
        assertThat(result.operations()).isEmpty();
        assertThat(result.page()).isZero();
        assertThat(repository.page(0, 10, "private-target-key", "").totalCount()).isZero();
    }

    private void insert(int id, String action, String status, Map<String, Object> request, Map<String, Object> result, String error) {
        Timestamp time = Timestamp.from(Instant.parse("2026-10-22T10:00:00Z"));
        jdbc.update("""
                insert into public.model_management_operations(id, action, status, actor_id, server_role, target_key,
                    request, result, error_code, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
                """, new UUID(0, id), action, status, new UUID(0, 999), "AI_INFERENCE", "private-target-key",
                mapper.writeValueAsString(request), mapper.writeValueAsString(result), error, time, time);
    }
}
