package com.doenaai.model.repository;

import com.doenaai.model.dto.ModelEvaluationRequest;
import com.doenaai.model.dto.ModelVersionCreateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 외부 DB 없이 실제 SQL 매개변수와 행 변환을 검사한다. */
class ModelCatalogRepositoryTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ModelCatalogRepository repository = new ModelCatalogRepository(jdbc, JsonMapper.builder().build());

    @SuppressWarnings("unchecked")
    @Test void versionDetailsReadOnlyApprovedColumnsAndDecodeJsonMetadata() throws Exception {
        UUID id = UUID.randomUUID();
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("id")).thenReturn(id);
        when(row.getObject("model_name")).thenReturn("분석 모델");
        when(row.getObject("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-21T00:00:00Z")));
        when(row.getObject("artifact_uri")).thenReturn("models/v1");
        when(row.getString("metadata")).thenReturn("{\"datasetName\":\"검토 데이터\",\"notes\":\"등록 기록\",\"privateField\":\"not-exported\"}");
        when(jdbc.query(anyString(), any(RowMapper.class), eq(id))).thenAnswer(call -> {
            RowMapper<Map<String, Object>> mapper = call.getArgument(1);
            return List.of(mapper.mapRow(row, 0));
        });
        var value = repository.version(id).orElseThrow();
        assertThat(value).containsEntry("id", id).containsEntry("modelName", "분석 모델")
                .containsEntry("artifactUri", "models/v1").containsEntry("datasetName", "검토 데이터")
                .containsEntry("notes", "등록 기록").containsEntry("createdAt", "2026-10-21T00:00:00Z")
                .doesNotContainKeys("privateField", "metadata", "endpointUrl");
        verify(jdbc).query(contains("where v.id = ?"), any(RowMapper.class), eq(id));
    }

    @SuppressWarnings("unchecked")
    @Test void everyCatalogQueryUsesBoundedParameterizedLimit() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(101))).thenReturn(List.of());
        repository.models(101);
        repository.versions(101);
        repository.trainingJobs(101);
        repository.deployments(101);
        verify(jdbc, times(4)).query(contains("limit ?"), any(RowMapper.class), eq(101));
    }

    @SuppressWarnings("unchecked")
    @Test void detailHistoriesAlsoUseTheExtraRowLimit() {
        UUID id = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(id), eq(101))).thenReturn(List.of());
        repository.evaluations(id);
        repository.versionDeployments(id);
        verify(jdbc, times(2)).query(contains("limit ?"), any(RowMapper.class), eq(id), eq(101));
    }

    @Test void registrationWritesReadyMetadataWithCurrentActorAndNeverExecutesPaths() {
        UUID id = UUID.randomUUID(), modelId = UUID.randomUUID(), actor = UUID.randomUUID();
        var request = new ModelVersionCreateRequest(modelId, "분석 모델", null, "MLX", "example/model", "v1", "LORA",
                "models/v1", "adapters/v1", "a".repeat(64), "검토 데이터", "등록 기록");
        repository.createVersion(id, modelId, request, actor);
        verify(jdbc).update(contains("'READY'"), eq(id), eq(modelId), eq("v1"), eq("LORA"), eq("example/model"),
                eq("models/v1"), eq("adapters/v1"), eq("a".repeat(64)),
                eq("{\"datasetName\":\"검토 데이터\",\"notes\":\"등록 기록\"}"), eq(actor));
        verifyNoMoreInteractions(jdbc);
    }

    @Test void manualEvaluationNeverUpdatesModelVersionOrDeployments() {
        UUID id = UUID.randomUUID(), versionId = UUID.randomUUID(), actor = UUID.randomUUID();
        repository.createEvaluation(id, versionId,
                new ModelEvaluationRequest("PASSED", BigDecimal.valueOf(90), "수동 확인", Map.of("sampleCount", 3)), actor);
        verify(jdbc).update(contains("'MANUAL'"), eq(id), eq(versionId), eq("PASSED"), eq("{\"sampleCount\":3}"),
                eq(BigDecimal.valueOf(90)), eq("수동 확인"), eq(actor));
        verifyNoMoreInteractions(jdbc);
    }
}
