package com.doenaai.model.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 서버별 잠금과 설정 버전 비교를 검사하며 외부 DB에 연결하지 않는다. */
class ModelHistoryRepositoryTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ModelHistoryRepository repository = new ModelHistoryRepository(jdbc, JsonMapper.builder().build());
    private final UUID versionId = UUID.randomUUID(), actor = UUID.randomUUID(), operation = UUID.randomUUID();
    private final String server = "f".repeat(64);

    @Test void replayOfTheSameOperationStopsBeforeChangingTheCurrentDeployment() {
        UUID id = ModelHistoryRepository.stableId("deployment\nai-inference-" + server + "\n" + operation);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(id))).thenReturn(true);
        repository.deployment(versionId, null, deployment(2));
        verify(jdbc).queryForObject(contains("pg_advisory_xact_lock"), any(RowMapper.class), anyLong());
        verify(jdbc).queryForObject(contains("where id = ?"), eq(Boolean.class), eq(id));
        verifyNoMoreInteractions(jdbc);
    }

    @Test void newerRevisionDeactivatesThePreviousDeploymentBeforeWritingTheNewActiveRow() {
        when(jdbc.queryForObject(contains("select max"), eq(Long.class), eq("ai-inference-" + server))).thenReturn(1L);
        repository.deployment(versionId, null, deployment(2));
        var order = inOrder(jdbc);
        order.verify(jdbc).queryForObject(contains("pg_advisory_xact_lock"), any(RowMapper.class), anyLong());
        order.verify(jdbc).update(contains("set status = 'INACTIVE'"), any(), eq("ai-inference-" + server));
        order.verify(jdbc).update(contains("insert into public.model_deployments"), any(UUID.class), eq(versionId), eq("ai-inference-" + server),
                eq("ACTIVE"), eq(actor), any(), anyString());
    }

    @Test void lateHistoricalRevisionIsInsertedInactiveWithoutTouchingTheCurrentDeployment() {
        when(jdbc.queryForObject(contains("select max"), eq(Long.class), eq("ai-inference-" + server))).thenReturn(5L);
        repository.deployment(versionId, null, deployment(2));
        verify(jdbc, never()).update(contains("set status = 'INACTIVE'"), any(), any());
        verify(jdbc).update(contains("insert into public.model_deployments"), any(UUID.class), eq(versionId), anyString(), eq("INACTIVE"), eq(actor), any(), anyString());
    }

    @Test void differentOperationsCannotClaimTheSameConfirmedSelectionRevision() {
        when(jdbc.queryForObject(contains("select max"), eq(Long.class), eq("ai-inference-" + server))).thenReturn(2L);
        assertThatThrownBy(() -> repository.deployment(versionId, null, deployment(2))).isInstanceOf(IllegalStateException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @SuppressWarnings("unchecked")
    @Test void registrationNeverSetsTheUnrelatedTrainingJobForeignKeyAndOnlyAddsMissingMetadata() {
        UUID modelId = UUID.randomUUID();
        when(jdbc.queryForObject(contains("select id from public.ai_models"), eq(UUID.class), eq("qwen3.8:27b"))).thenReturn(modelId);
        when(jdbc.query(contains("insert into public.model_versions"), any(RowMapper.class), any(Object[].class))).thenReturn(List.of(versionId));
        assertThat(repository.registration(version())).isEqualTo(versionId);
        var calls = mockingDetails(jdbc).getInvocations();
        String sql = calls.stream().map(call -> call.<String>getArgument(0)).filter(value -> value.contains("insert into public.model_versions")).findFirst().orElseThrow();
        assertThat(sql).doesNotContain("training_job_id").contains("excluded.metadata - 'registeredObservedAt'", "returning id");
    }

    @Test void stableIdentifiersAreDeterministicAndUseDistinctNamespaces() {
        assertThat(ModelHistoryRepository.stableId("deployment\n" + operation)).isEqualTo(ModelHistoryRepository.stableId("deployment\n" + operation));
        assertThat(ModelHistoryRepository.stableId("deployment\n" + operation)).isNotEqualTo(ModelHistoryRepository.stableId("version\n" + operation));
    }

    private ModelHistoryRepository.Deployment deployment(long revision) {
        return new ModelHistoryRepository.Deployment(operation, server, revision, actor, Instant.parse("2026-10-22T06:00:00Z"),
                Map.of("historySource", "MODEL_EXECUTION", "selectionRevision", revision));
    }

    private ModelHistoryRepository.Version version() {
        return new ModelHistoryRepository.Version("qwen3.8:27b", "installed-" + "a".repeat(64), "BASE", "qwen3.8:27b", "a".repeat(64), actor,
                Map.of("engineDigest", "a".repeat(64), "registeredObservedAt", "2026-10-22T06:00:00Z"));
    }
}
