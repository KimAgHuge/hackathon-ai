package com.doenaai.analysis.repository;

import com.doenaai.analysis.dto.AnalysisJobResponse;
import com.doenaai.analysis.dto.AnalysisWorkItem;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Opt-in tests for a disposable PostgreSQL database, never the application datasource.
 * Set DOENAAI_TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:PORT/doenaai_worker_test.
 * Fixture credentials are postgres / doenaai-worker-test. Existing test tables are refused.
 * These fixtures mirror the columns/checks used from migrations 002 and 007, omitting
 * foreign keys to unrelated project, requirement, model, and auth tables and their RLS.
 */
@EnabledIfEnvironmentVariable(named = "DOENAAI_TEST_POSTGRES_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class AnalysisPipelineIntegrationTest {

    private static final Pattern SAFE_DATABASE_URL = Pattern.compile(
            "^jdbc:postgresql://127\\.0\\.0\\.1:([1-9][0-9]{0,4})/doenaai_worker_test$"
    );
    private static final UUID PROJECT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID REQUIREMENT_VERSION_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final String REPOSITORY_URL = "https://example.invalid/fixture/repository.git";
    private static final String BRANCH = "feature/integration-fixture";
    private static final String COMMIT_SHA = "0123456789abcdef0123456789abcdef01234567";

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private AnalysisJobRepository jobs;
    private AnalysisResultRepository results;
    private boolean ownsTables;

    @BeforeAll
    void createFixtureTables() {
        String url = System.getenv("DOENAAI_TEST_POSTGRES_URL");
        var match = SAFE_DATABASE_URL.matcher(url == null ? "" : url);
        if (!match.matches() || Integer.parseInt(match.group(1)) > 65535) {
            throw new IllegalArgumentException(
                    "Refusing database access: use exactly jdbc:postgresql://127.0.0.1:PORT/doenaai_worker_test"
            );
        }

        dataSource = new DriverManagerDataSource(url, "postgres", "doenaai-worker-test");
        Properties connectionProperties = new Properties();
        connectionProperties.setProperty("connectTimeout", "5");
        connectionProperties.setProperty("socketTimeout", "20");
        connectionProperties.setProperty("ApplicationName", "doenaai-analysis-pipeline-fixture");
        dataSource.setConnectionProperties(connectionProperties);
        jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("doenaai_worker_test");
        assertThat(jdbc.queryForObject("select current_user", String.class)).isEqualTo("postgres");

        var transactionManager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(transactionManager);
        jobs = transactional(new AnalysisJobRepository(jdbc), AnalysisJobRepository.class, transactionManager);
        results = transactional(new AnalysisResultRepository(jdbc, JsonMapper.builder().build()),
                AnalysisResultRepository.class, transactionManager);

        transactions.executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("select to_regclass('public.analysis_jobs') is null", Boolean.class))
                    .as("Refuse to replace an existing analysis_jobs table")
                    .isTrue();
            assertThat(jdbc.queryForObject("select to_regclass('public.analysis_results') is null", Boolean.class))
                    .as("Refuse to replace an existing analysis_results table")
                    .isTrue();

            jdbc.execute("""
                    create table public.analysis_jobs (
                        id uuid primary key default gen_random_uuid(),
                        project_id uuid not null,
                        requirement_version_id uuid,
                        requested_by uuid,
                        analysis_type varchar(30) not null default 'CHANGE_REQUEST'
                            check (analysis_type in ('CHANGE_REQUEST', 'PROJECT_SCAN', 'REQUIREMENT', 'REANALYSIS')),
                        status varchar(20) not null default 'PENDING'
                            check (status in ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
                        source_progress integer not null default 0 check (source_progress between 0 and 100),
                        database_progress integer not null default 0 check (database_progress between 0 and 100),
                        relation_progress integer not null default 0 check (relation_progress between 0 and 100),
                        analysis_options jsonb not null default '{}'::jsonb,
                        input_snapshot jsonb not null default '{}'::jsonb,
                        error_message text,
                        started_at timestamptz,
                        completed_at timestamptz,
                        created_at timestamptz not null default now(),
                        updated_at timestamptz not null default now()
                    )
                    """);
            jdbc.execute("""
                    create table public.analysis_results (
                        id uuid primary key default gen_random_uuid(),
                        analysis_job_id uuid not null unique references public.analysis_jobs(id) on delete cascade,
                        original_result jsonb not null,
                        corrected_result jsonb,
                        risk_level varchar(20)
                            check (risk_level is null or risk_level in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL', 'UNKNOWN')),
                        summary text,
                        approved boolean not null default false,
                        approved_by uuid,
                        approved_at timestamptz,
                        review_note text,
                        inference_metadata jsonb not null default '{}'::jsonb,
                        created_at timestamptz not null default now(),
                        updated_at timestamptz not null default now()
                    )
                    """);
        });
        ownsTables = true;
    }

    @BeforeEach
    void clearFixtures() {
        assertThat(ownsTables).isTrue();
        transactions.executeWithoutResult(status -> {
            jdbc.update("delete from public.analysis_results");
            jdbc.update("delete from public.analysis_jobs");
        });
    }

    @AfterAll
    void dropFixtureTables() {
        if (!ownsTables) return;
        transactions.executeWithoutResult(status -> {
            jdbc.execute("drop table public.analysis_results");
            jdbc.execute("drop table public.analysis_jobs");
        });
        ownsTables = false;
    }

    @Test
    void createsAndClaimsOnlyTheOldestEligibleProjectScanWithItsSnapshot() {
        var ignored = newJob();
        jdbc.update("update public.analysis_jobs set analysis_type = 'CHANGE_REQUEST', created_at = now() - interval '3 minutes' where id = ?",
                ignored.jobId());
        var later = newJob();
        var oldest = newJob();
        jdbc.update("update public.analysis_jobs set created_at = now() - interval '2 minutes', error_message = 'stale fixture message' where id = ?",
                oldest.jobId());

        assertThat(oldest.status()).isEqualTo("PENDING");
        assertThat(oldest.startedAt()).isNull();
        assertThat(oldest.completedAt()).isNull();
        assertThat(oldest.overallProgress()).isZero();
        assertThat(oldest.requirementVersionId()).isEqualTo(REQUIREMENT_VERSION_ID);
        assertThat(jobs.findById(UUID.randomUUID())).isEmpty();

        AnalysisWorkItem claimed = jobs.claimNext().orElseThrow();
        assertThat(claimed).isEqualTo(new AnalysisWorkItem(oldest.jobId(), PROJECT_ID, REQUIREMENT_VERSION_ID,
                USER_ID, REPOSITORY_URL, BRANCH, COMMIT_SHA));
        var running = jobs.findById(oldest.jobId()).orElseThrow();
        assertThat(running.status()).isEqualTo("RUNNING");
        assertThat(running.startedAt()).isNotNull();
        assertThat(running.completedAt()).isNull();
        assertThat(running.errorMessage()).isNull();
        assertThat(jobs.claimNext().orElseThrow().jobId()).isEqualTo(later.jobId());
        assertThat(jobs.claimNext()).isEmpty();
        assertThat(jobs.findById(ignored.jobId()).orElseThrow().status()).isEqualTo("PENDING");
    }

    @Test
    void concurrentTransactionsSkipLockedRowsAndClaimDistinctJobs() throws Exception {
        var firstJob = newJob();
        var secondJob = newJob();
        var start = new CountDownLatch(1);
        var bothClaimed = new CountDownLatch(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> claimWhileHoldingLock(start, bothClaimed));
            var second = executor.submit(() -> claimWhileHoldingLock(start, bothClaimed));
            start.countDown();

            AnalysisWorkItem firstClaim = first.get(15, TimeUnit.SECONDS);
            AnalysisWorkItem secondClaim = second.get(15, TimeUnit.SECONDS);
            assertThat(List.of(firstClaim.jobId(), secondClaim.jobId()))
                    .containsExactlyInAnyOrder(firstJob.jobId(), secondJob.jobId());
            assertThat(jobs.findById(firstJob.jobId()).orElseThrow().status()).isEqualTo("RUNNING");
            assertThat(jobs.findById(secondJob.jobId()).orElseThrow().status()).isEqualTo("RUNNING");
            assertThat(jobs.claimNext()).isEmpty();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void progressAndFailurePreserveTerminalStatusAndExposeActualTimestamps() {
        var pending = newJob();
        assertThatThrownBy(() -> jobs.progress(pending.jobId(), 10, 20, 30))
                .isInstanceOf(IllegalStateException.class);
        var job = jobs.claimNext().orElseThrow();
        jobs.progress(job.jobId(), 40, 80, 15);
        var progress = jobs.findById(job.jobId()).orElseThrow();
        assertThat(progress.sourceProgress()).isEqualTo(40);
        assertThat(progress.databaseProgress()).isEqualTo(80);
        assertThat(progress.relationProgress()).isEqualTo(15);
        assertThat(progress.overallProgress()).isEqualTo(45);

        jobs.fail(job.jobId(), "Fixture database connection failed.");
        var failed = jobs.findById(job.jobId()).orElseThrow();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorMessage()).isEqualTo("Fixture database connection failed.");
        assertThat(failed.startedAt()).isNotNull();
        assertThat(failed.completedAt()).isNotNull();
        assertThat(failed.completedAt()).isAfterOrEqualTo(failed.startedAt());
        assertThat(failed.overallProgress()).isEqualTo(45);
        assertThatThrownBy(() -> jobs.progress(job.jobId(), 100, 100, 100)).isInstanceOf(IllegalStateException.class);
        jobs.fail(job.jobId(), "This must not replace the first terminal error.");
        assertThat(jobs.findById(job.jobId()).orElseThrow()).isEqualTo(failed);
        assertThat(jobs.claimNext()).isEmpty();
        assertThat(results.findByJobId(job.jobId())).isEmpty();
    }

    @Test
    void staleRecoveryFailsOnlyProjectScansWithoutRecentProgress() {
        var stale = newJob();
        var recent = newJob();
        var otherType = newJob();
        var pending = newJob();
        var completed = newJob();
        jdbc.update("""
                update public.analysis_jobs set status = 'RUNNING', started_at = now() - interval '2 hours',
                    updated_at = now() - interval '2 minutes', source_progress = 75
                where id in (?, ?, ?)
                """, stale.jobId(), recent.jobId(), otherType.jobId());
        // A long-running job with a recent progress heartbeat is not abandoned.
        jobs.progress(recent.jobId(), 90, 100, 40);
        jdbc.update("update public.analysis_jobs set analysis_type = 'CHANGE_REQUEST' where id = ?", otherType.jobId());
        jdbc.update("update public.analysis_jobs set updated_at = now() - interval '2 minutes' where id = ?", pending.jobId());
        jdbc.update("""
                update public.analysis_jobs set status = 'COMPLETED', updated_at = now() - interval '2 minutes'
                where id = ?
                """, completed.jobId());
        var recentBefore = jobs.findById(recent.jobId()).orElseThrow();

        jobs.failStale(60);

        var failed = jobs.findById(stale.jobId()).orElseThrow();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.sourceProgress()).isEqualTo(75);
        assertThat(failed.errorMessage()).isNotBlank();
        assertThat(failed.completedAt()).isNotNull();
        assertThat(jobs.findById(recent.jobId()).orElseThrow()).isEqualTo(recentBefore);
        assertThat(jobs.findById(otherType.jobId()).orElseThrow().status()).isEqualTo("RUNNING");
        assertThat(jobs.findById(pending.jobId()).orElseThrow().status()).isEqualTo("PENDING");
        assertThat(jobs.findById(completed.jobId()).orElseThrow().status()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select count(*) from public.analysis_jobs where status = 'FAILED'", Integer.class)).isEqualTo(1);
        jobs.failStale(60);
        assertThat(jobs.findById(stale.jobId()).orElseThrow()).isEqualTo(failed);
    }

    @Test
    void completionAndResultBecomeVisibleTogetherAfterCommitAndRoundTripJson() {
        var pending = newJob();
        jobs.claimNext().orElseThrow();
        var result = validResult();
        var facts = validFacts();

        transactions.executeWithoutResult(status -> {
            results.complete(pending.jobId(), result, facts);
            assertThat(jobs.findById(pending.jobId()).orElseThrow().status()).isEqualTo("COMPLETED");
            assertThat(results.findByJobId(pending.jobId())).isPresent();
            assertCommittedState(pending.jobId(), "RUNNING", 0);
        });

        assertCommittedState(pending.jobId(), "COMPLETED", 1);
        var completed = jobs.findById(pending.jobId()).orElseThrow();
        assertThat(completed.sourceProgress()).isEqualTo(100);
        assertThat(completed.databaseProgress()).isEqualTo(100);
        assertThat(completed.relationProgress()).isEqualTo(100);
        assertThat(completed.overallProgress()).isEqualTo(100);
        assertThat(completed.errorMessage()).isNull();
        assertThat(completed.startedAt()).isNotNull();
        assertThat(completed.completedAt()).isNotNull();

        var saved = results.findByJobId(pending.jobId()).orElseThrow();
        assertThat(saved.jobId()).isEqualTo(pending.jobId());
        assertThat(saved.projectId()).isEqualTo(PROJECT_ID);
        assertThat(saved.summary()).isEqualTo(result.get("summary"));
        assertThat(saved.riskLevel()).isEqualTo("MEDIUM");
        assertThat(saved.riskScore()).isEqualTo(42);
        assertThat(saved.result()).isEqualTo(result);
        assertThat(saved.facts()).isEqualTo(facts);
        assertThat(saved.createdAt()).isNotNull();
        assertThat(results.findByJobId(UUID.randomUUID())).isEmpty();

        assertThatThrownBy(() -> results.complete(pending.jobId(), result, facts)).isInstanceOf(IllegalStateException.class);
        jobs.fail(pending.jobId(), "A late failure must not replace a completed result.");
        assertThat(jobs.findById(pending.jobId()).orElseThrow()).isEqualTo(completed);
        assertCommittedState(pending.jobId(), "COMPLETED", 1);
    }

    @Test
    void resultInsertConstraintFailureRollsBackTheCompletedStatusAndAllProgress() {
        var pending = newJob();
        jobs.claimNext().orElseThrow();
        jobs.progress(pending.jobId(), 35, 55, 10);
        var before = jobs.findById(pending.jobId()).orElseThrow();
        Map<String, Object> invalidResult = Map.of(
                "summary", "The fixture intentionally violates the real risk_level CHECK.",
                "riskLevel", "INVALID",
                "riskScore", 42
        );

        // No surrounding test transaction: the repository's actual @Transactional proxy must roll back.
        assertThatThrownBy(() -> results.complete(pending.jobId(), invalidResult, validFacts()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jobs.findById(pending.jobId()).orElseThrow()).isEqualTo(before);
        assertCommittedState(pending.jobId(), "RUNNING", 0);
        assertThat(results.findByJobId(pending.jobId())).isEmpty();

        results.complete(pending.jobId(), validResult(), validFacts());
        assertCommittedState(pending.jobId(), "COMPLETED", 1);
    }

    @Test
    void onlyCompletedJobsExposeResultsAndInvalidTransitionsAreRejected() {
        var pending = newJob();
        assertThatThrownBy(() -> results.complete(pending.jobId(), validResult(), validFacts()))
                .isInstanceOf(IllegalStateException.class);
        assertCommittedState(pending.jobId(), "PENDING", 0);

        jdbc.update("""
                insert into public.analysis_results (analysis_job_id, original_result, inference_metadata)
                values (?, cast(? as jsonb), cast(? as jsonb))
                """, pending.jobId(), JsonMapper.builder().build().writeValueAsString(validResult()), "{}");
        assertThat(results.findByJobId(pending.jobId())).isEmpty();
        jobs.claimNext().orElseThrow();
        assertThat(results.findByJobId(pending.jobId())).isEmpty();
        jobs.fail(pending.jobId(), "Fixture failure");
        assertThat(results.findByJobId(pending.jobId())).isEmpty();
        assertThatThrownBy(() -> results.complete(pending.jobId(), validResult(), validFacts()))
                .isInstanceOf(IllegalStateException.class);
        assertCommittedState(pending.jobId(), "FAILED", 1);
    }

    private AnalysisJobResponse newJob() {
        return jobs.createProjectScan(PROJECT_ID, REQUIREMENT_VERSION_ID, USER_ID, REPOSITORY_URL, BRANCH, COMMIT_SHA);
    }

    private AnalysisWorkItem claimWhileHoldingLock(CountDownLatch start, CountDownLatch bothClaimed) {
        return transactions.execute(status -> {
            await(start);
            AnalysisWorkItem claimed = jobs.claimNext().orElseThrow();
            bothClaimed.countDown();
            // A second worker must claim another row before either transaction releases its row lock.
            await(bothClaimed);
            return claimed;
        });
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).as("Concurrent claims must not block each other").isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for concurrent fixture claims", exception);
        }
    }

    private void assertCommittedState(UUID jobId, String expectedStatus, int expectedResultCount) {
        // Deliberately bypass DataSourceUtils: this observer must not reuse the caller's transaction connection.
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                select j.status, (select count(*) from public.analysis_results r where r.analysis_job_id = j.id) as result_count
                from public.analysis_jobs j where j.id = ?
                """)) {
            statement.setObject(1, jobId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("status")).isEqualTo(expectedStatus);
                assertThat(rows.getInt("result_count")).isEqualTo(expectedResultCount);
                assertThat(rows.next()).isFalse();
            }
        } catch (SQLException exception) {
            throw new AssertionError("Could not inspect committed fixture state", exception);
        }
    }

    private static <T> T transactional(T target, Class<T> type, DataSourceTransactionManager transactionManager) {
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(transactionManager);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        advice.afterPropertiesSet();
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(advice);
        return type.cast(factory.getProxy());
    }

    private Map<String, Object> validResult() {
        return Map.of(
                "summary", "사용자 조회 API와 users 테이블을 함께 검토하세요.",
                "frameworks", List.of("Spring Boot"),
                "riskLevel", "MEDIUM",
                "riskScore", 42,
                "impactAreas", List.of("사용자 조회"),
                "evidences", List.of(Map.of("file", "UserRepository.java", "line", 12, "table", "users")),
                "recommendations", List.of("사용자 조회 통합 테스트를 추가하세요.")
        );
    }

    private Map<String, Object> validFacts() {
        return Map.of(
                "sourceSummary", Map.of("languages", List.of("Java"), "components", List.of(Map.of("file", "UserRepository.java"))),
                "databaseSummary", Map.of("type", "POSTGRESQL", "tables", List.of(Map.of("name", "users"))),
                "relations", List.of(Map.of("file", "UserRepository.java", "table", "users"))
        );
    }
}
