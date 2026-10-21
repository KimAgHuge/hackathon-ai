package com.doenaai.model.service;

import com.doenaai.model.dto.ModelExecutionRequest;
import com.doenaai.model.repository.ModelOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModelExecutionServiceTest {
    private final ModelOperationRepository repository = mock(ModelOperationRepository.class);
    private final ModelExecutionClient client = mock(ModelExecutionClient.class);
    private final ModelExecutionService service = new ModelExecutionService(repository, client);
    private final UUID actor = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();
    private final String digest = "a".repeat(64);
    private final Map<UUID, Map<String, Object>> records = new HashMap<>();

    @BeforeEach void setup() {
        when(client.targetKey(false)).thenReturn("inference-target");
        when(client.targetKey(true)).thenReturn("training-target");
        when(client.inference("GET", "/api/models/installed", Map.of(), false)).thenReturn(Map.of("model", "qwen3.8:27b", "models", List.of(Map.of("name", "qwen3.8:27b"), Map.of("name", "gemma4:26b"))));
        when(repository.create(any(), anyString(), any(), anyString(), anyString(), anyMap())).thenAnswer(call -> {
            UUID key = call.getArgument(0);
            if (records.containsKey(key)) return false;
            var row = new LinkedHashMap<String, Object>();
            row.put("id", key.toString()); row.put("action", call.getArgument(1)); row.put("actorId", call.getArgument(2).toString());
            row.put("serverRole", call.getArgument(3)); row.put("targetKey", call.getArgument(4)); row.put("request", call.getArgument(5));
            row.put("status", "PENDING"); row.put("result", Map.of()); records.put(key, row); return true;
        });
        when(repository.find(any())).thenAnswer(call -> Optional.ofNullable(records.get(call.getArgument(0))));
        when(repository.record(any(), anyString(), nullable(String.class), anyMap())).thenAnswer(call -> {
            var row = new LinkedHashMap<>(records.get(call.getArgument(0)));
            row.put("status", call.getArgument(1)); row.put("errorCode", call.getArgument(2)); row.put("result", call.getArgument(3));
            records.put(call.getArgument(0), row); return row;
        });
    }

    @Test void databaseFailurePreventsAnyRemoteExecution() {
        doThrow(new DataAccessResourceFailureException("synthetic")).when(repository)
                .create(any(), anyString(), any(), anyString(), anyString(), anyMap());
        assertThatThrownBy(() -> service.execute("TRAINING_START", training(id, 10), actor, null)).isInstanceOf(DataAccessResourceFailureException.class);
        verify(client, never()).training(any(), any(), any());
        verify(client, never()).inference(any(), any(), any(), anyBoolean());
    }

    @Test void interruptedResponseRemainsUnknownAndDoesNotAutomaticallyRepeatStart() {
        when(client.training(eq("POST"), eq("/api/training/jobs"), anyMap()))
                .thenThrow(new ModelExecutionClient.RemoteFailure("REMOTE_UNREACHABLE", true, 503));
        assertThat(service.execute("TRAINING_START", training(id, 10), actor, null)).containsEntry("status", "UNKNOWN");
        when(client.training(eq("GET"), anyString(), anyMap())).thenThrow(new ModelExecutionClient.RemoteFailure("REMOTE_UNREACHABLE", true, 503));
        assertThat(service.operation(id)).containsEntry("status", "UNKNOWN");
        verify(client, times(1)).training(eq("POST"), anyString(), anyMap());
    }

    @Test void explicitSameIdRetryCanRecoverAnInterruptedDispatchWithoutCreatingAnotherJob() {
        when(client.training(eq("POST"), eq("/api/training/jobs"), anyMap()))
                .thenThrow(new ModelExecutionClient.RemoteFailure("REMOTE_UNREACHABLE", true, 503));
        service.execute("TRAINING_START", training(id, 10), actor, null);
        when(client.training(eq("GET"), anyString(), anyMap())).thenThrow(new ModelExecutionClient.RemoteFailure("NOT_FOUND", false, 404));
        doReturn(Map.of("status", "PENDING")).when(client).training(eq("POST"), eq("/api/training/jobs"), anyMap());
        assertThat(service.execute("TRAINING_START", training(id, 10), actor, null)).containsEntry("status", "PENDING");
        verify(client, times(2)).training(eq("POST"), eq("/api/training/jobs"), argThat(body -> id.toString().equals(body.get("jobId"))));
        assertThat(records).hasSize(1);
    }

    @Test void successfulProbeTokenNeverEntersPersistentAuditFields() {
        String token = "temporary_probe_token_123456789";
        when(client.inference(eq("POST"), eq("/api/models/probe"), anyMap(), eq(true)))
                .thenReturn(Map.of("status", "PASSED", "model", "example:1b", "digest", digest, "probeToken", token,
                        "expiresAt", "2026-10-21T09:00:00Z", "currentRevision", 0));
        var result = service.execute("MODEL_PROBE", new ModelExecutionRequest(id, "example:1b", digest, null, null, null), actor, null);
        assertThat(result).containsEntry("probeToken", token).containsEntry("status", "PASSED");
        assertThat(records.get(id).toString()).doesNotContain(token);
        assertThat(((Map<?, ?>) records.get(id).get("request")).containsKey("probeToken")).isFalse();
    }

    @Test void numericJsonRoundTripDoesNotBreakIdempotentSwitchRequest() {
        var original = new LinkedHashMap<String, Object>();
        original.put("id", id.toString()); original.put("action", "MODEL_ACTIVATE"); original.put("actorId", actor.toString());
        original.put("targetKey", "inference-target"); original.put("status", "APPLIED"); original.put("result", Map.of("model", "example:1b"));
        String hash;
        try { hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("temporary_probe_123456".getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new AssertionError(failure); }
        original.put("request", Map.of("model", "example:1b", "digest", digest, "expectedRevision", 0, "probeTokenHash", hash)); records.put(id, original);
        assertThat(service.execute("MODEL_ACTIVATE", new ModelExecutionRequest(id, "example:1b", digest, "temporary_probe_123456", 0L, null), actor, null))
                .containsEntry("status", "APPLIED");
        verify(client, never()).inference(any(), any(), any(), anyBoolean());
    }

    @Test void sameIdDifferentIterationsOrActorIsRejectedBeforeSecondExecution() {
        when(client.training(eq("POST"), anyString(), anyMap())).thenReturn(Map.of("status", "PENDING"));
        service.execute("TRAINING_START", training(id, 10), actor, null);
        assertThatThrownBy(() -> service.execute("TRAINING_START", training(id, 11), actor, null)).isInstanceOf(ModelCatalogException.class);
        assertThatThrownBy(() -> service.execute("TRAINING_START", training(id, 10), UUID.randomUUID(), null)).isInstanceOf(ModelCatalogException.class);
        verify(client, times(1)).training(eq("POST"), anyString(), anyMap());
    }

    @Test void uncertainModelSwitchReconcilesByOperationIdWithoutAnotherPost() {
        when(client.inference(eq("POST"), eq("/api/models/activate"), anyMap(), eq(false)))
                .thenThrow(new ModelExecutionClient.RemoteFailure("REMOTE_UNREACHABLE", true, 503));
        var request = new ModelExecutionRequest(id, "example:1b", digest, "temporary_probe_123456", 0L, null);
        service.execute("MODEL_ACTIVATE", request, actor, null);
        when(client.inference(eq("GET"), eq("/api/models/operations/" + id), anyMap(), eq(false)))
                .thenReturn(Map.of("status", "APPLIED", "model", "example:1b", "previousModel", "example:old", "revision", 1));
        assertThat(service.operation(id)).containsEntry("status", "APPLIED").containsEntry("model", "example:1b");
        verify(client, times(1)).inference(eq("POST"), anyString(), anyMap(), anyBoolean());
    }

    @Test void historyOnAnotherBackendNeverQueriesItsLocalServerForForeignWork() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id.toString()); row.put("action", "MODEL_ACTIVATE"); row.put("status", "UNKNOWN");
        row.put("targetKey", "another-backend"); row.put("result", Map.of()); records.put(id, row);
        service.operation(id);
        verify(client, never()).inference(any(), any(), any(), anyBoolean());
    }

    @Test void malformedInputIsRejectedBeforeDatabaseOrRemoteRequests() {
        assertThatThrownBy(() -> service.execute("TRAINING_START", training(id, 0), actor, null)).isInstanceOf(ModelCatalogException.class);
        assertThatThrownBy(() -> service.execute("MODEL_PROBE", new ModelExecutionRequest(id, "https://secret@example", digest, null, null, null), actor, null))
                .isInstanceOf(ModelCatalogException.class);
        verify(repository, never()).create(any(), any(), any(), any(), any(), any());
        verify(client, never()).training(any(), any(), any());
    }

    @Test void unconfiguredServerProducesARecordedFailureWithoutLeakingRemoteContent() {
        when(client.training(eq("POST"), anyString(), anyMap())).thenThrow(new ModelExecutionClient.RemoteFailure("UNCONFIGURED", false, 503));
        var result = service.execute("TRAINING_START", training(id, 10), actor, null);
        assertThat(result).containsEntry("status", "FAILED").containsEntry("errorCode", "UNCONFIGURED");
        assertThat(result).doesNotContainKeys("targetKey", "request", "result");
    }

    @Test void trainingCatalogAndHistoryOnlyExposeTheMatchingSourceModel() {
        var target = Map.<String, Object>of("sourceModel", "qwen3.8:27b", "baseModel", "mlx-community/Qwen3.8-27B-4bit",
                "revision", "a".repeat(40), "digest", "b".repeat(64));
        when(client.training("GET", "/api/training/capabilities?model=qwen3.8%3A27b", Map.of())).thenReturn(Map.of("trainingExecution", true,
                "dataset", Map.of("target", target, "samples", List.of(
                        Map.of("caseId", "qwen-case", "sourceModel", "qwen3.8:27b"),
                        Map.of("caseId", "other-case", "sourceModel", "gemma4:26b")), "total", 1)));
        when(client.training("GET", "/api/training/jobs", Map.of())).thenReturn(Map.of("jobs", List.of()));
        when(repository.training(eq("training-target"), anyString(), eq(101))).thenReturn(List.of(
                Map.of("id", "matching", "status", "SUCCEEDED", "result", Map.of("target", target)),
                Map.of("id", "legacy", "status", "SUCCEEDED", "result", Map.of())));
        var result = service.training();
        assertThat(result.toString()).contains("qwen-case", "matching").doesNotContain("other-case", "legacy");
    }

    @Test void changedInferenceModelDoesNotReplaceTheChosenTrainingModel() {
        when(client.inference("GET", "/api/models/installed", Map.of(), false)).thenReturn(Map.of("model", "gemma4:26b", "models", List.of(Map.of("name", "qwen3.8:27b"))));
        when(client.training(eq("POST"), eq("/api/training/jobs"), anyMap())).thenReturn(Map.of("status", "PENDING"));
        assertThat(service.execute("TRAINING_START", training(id, 10), actor, null)).containsEntry("status", "PENDING");
        verify(client).training(eq("POST"), eq("/api/training/jobs"), argThat(body -> "qwen3.8:27b".equals(body.get("model"))));
    }

    @Test void missingInstalledSelectionRejectsTrainingBeforeRemoteStart() {
        when(client.inference("GET", "/api/models/installed", Map.of(), false)).thenReturn(Map.of("model", "gemma4:26b", "models", List.of(Map.of("name", "gemma4:26b"))));
        assertThat(service.execute("TRAINING_START", training(id, 10), actor, null))
                .containsEntry("status", "FAILED").containsEntry("errorCode", "TRAINING_MODEL_NOT_INSTALLED");
        verify(client, never()).training(eq("POST"), anyString(), anyMap());
    }

    @Test void modelChangeDoesNotTurnAnUncertainExistingStartIntoADefiniteFailure() {
        when(client.training(eq("POST"), eq("/api/training/jobs"), anyMap()))
                .thenThrow(new ModelExecutionClient.RemoteFailure("REMOTE_UNREACHABLE", true, 503));
        service.execute("TRAINING_START", training(id, 10), actor, null);
        when(client.inference("GET", "/api/models/installed", Map.of(), false)).thenReturn(Map.of("model", "gemma4:26b", "models", List.of(Map.of("name", "gemma4:26b"))));
        when(client.training(eq("GET"), anyString(), anyMap()))
                .thenThrow(new ModelExecutionClient.RemoteFailure("REMOTE_UNREACHABLE", true, 503));
        assertThat(service.execute("TRAINING_START", training(id, 10), actor, null))
                .containsEntry("status", "UNKNOWN").containsEntry("errorCode", "TRAINING_MODEL_NOT_INSTALLED");
        verify(client, times(1)).training(eq("POST"), eq("/api/training/jobs"), anyMap());
    }

    @Test void selectedInferenceModelIsSentToTrainingCapabilitiesWithoutQwenFallback() {
        when(client.inference("GET", "/api/models/installed", Map.of(), false)).thenReturn(Map.of("model", "ingu627/exaone4.0:32b"));
        when(client.training("GET", "/api/training/capabilities?model=ingu627%2Fexaone4.0%3A32b", Map.of()))
                .thenReturn(Map.of("trainingExecution", false, "trainingReasonCode", "MODEL_NOT_REGISTERED"));
        when(client.training("GET", "/api/training/jobs", Map.of())).thenReturn(Map.of("jobs", List.of()));
        when(repository.training(eq("training-target"), anyString(), eq(101))).thenReturn(List.of());
        var result = service.training();
        assertThat(result).containsEntry("requestedModel", "ingu627/exaone4.0:32b");
        assertThat((Map<?, ?>) result.get("dataset")).isEmpty();
        assertThat(result.toString()).contains("등록되지 않았습니다");
        verify(client, never()).training("GET", "/api/training/capabilities?model=qwen3.8%3A27b", Map.of());
    }

    @Test void selectedInstalledModelIsQueriedWithoutChangingTheInferenceModel() {
        when(client.training("GET", "/api/training/capabilities?model=gemma4%3A26b", Map.of()))
                .thenReturn(Map.of("trainingExecution", false, "trainingReasonCode", "MODEL_NOT_REGISTERED"));
        when(client.training("GET", "/api/training/jobs", Map.of())).thenReturn(Map.of("jobs", List.of()));
        var result = service.training("gemma4:26b");
        assertThat(result).containsEntry("requestedModel", "gemma4:26b").containsEntry("selectedModel", "gemma4:26b");
        verify(client, never()).inference(eq("POST"), anyString(), anyMap(), anyBoolean());
    }

    @Test void unknownOrUnsafeSelectionIsRejectedWithoutTrainingRequests() {
        assertThatThrownBy(() -> service.training("unknown:1b")).isInstanceOf(ModelCatalogException.class);
        assertThatThrownBy(() -> service.training("https://host/model")).isInstanceOf(ModelCatalogException.class);
        verify(client, never()).training(any(), any(), anyMap());
    }

    @Test void selectingAnAdapterUsesItsSourceModelForTraining() {
        String adapter = UUID.randomUUID().toString();
        String name = "doenaai-mlx:web-" + adapter;
        when(client.inference("GET", "/api/models/installed", Map.of(), false)).thenReturn(Map.of("model", name,
                "provider", "mlx", "adapterId", adapter, "sourceModel", "qwen3.8:27b",
                "models", List.of(Map.of("name", name, "provider", "mlx", "adapterId", adapter, "sourceModel", "qwen3.8:27b"))));
        when(client.training("GET", "/api/training/capabilities?model=qwen3.8%3A27b", Map.of())).thenReturn(Map.of("trainingExecution", false));
        when(client.training("GET", "/api/training/jobs", Map.of())).thenReturn(Map.of("jobs", List.of()));
        assertThat(service.training(name)).containsEntry("requestedModel", "qwen3.8:27b").containsEntry("selectedModel", name);
        assertThat(service.training()).containsEntry("requestedModel", "qwen3.8:27b");
    }

    @Test void otherModelActiveJobsStayVisibleAndBlockConcurrentTraining() {
        var target = Map.<String, Object>of("sourceModel", "gemma4:26b", "baseModel", "example/gemma", "revision", "b".repeat(40), "digest", digest);
        var active = Map.<String, Object>of("id", id.toString(), "status", "TRAINING", "result", Map.of("target", target));
        when(client.training("GET", "/api/training/capabilities?model=qwen3.8%3A27b", Map.of())).thenReturn(Map.of("trainingExecution", false));
        when(client.training("GET", "/api/training/jobs", Map.of())).thenReturn(Map.of("jobs", List.of()));
        when(repository.activeTraining("training-target")).thenReturn(List.of(active));
        var result = service.training();
        assertThat(result).containsEntry("busy", true);
        assertThat(result.get("jobs").toString()).contains(id.toString(), "gemma4:26b");
        assertThat(result.get("activeJobs").toString()).contains(id.toString());
    }

    @Test void adapterMetadataIsPassedWithoutRemotePathsOrQualityClaims() {
        String adapter = UUID.randomUUID().toString();
        String name = "doenaai-mlx:web-" + adapter;
        var entry = Map.<String, Object>of("name", name, "provider", "mlx", "adapterId", adapter,
                "sourceModel", "qwen3.8:27b", "qualityEvaluated", true, "path", "/secret/model", "selectable", true);
        when(client.inference("GET", "/api/models/installed", Map.of(), false)).thenReturn(Map.of("model", name,
                "provider", "mlx", "adapterId", adapter, "sourceModel", "qwen3.8:27b", "models", List.of(entry)));
        var result = service.installed();
        assertThat(result).containsEntry("provider", "mlx").containsEntry("sourceModel", "qwen3.8:27b").containsEntry("qualityEvaluated", false);
        assertThat(result.toString()).doesNotContain("/secret/model", "qualityEvaluated=true");
    }

    private static ModelExecutionRequest training(UUID id, int count) { return new ModelExecutionRequest(id, "qwen3.8:27b", "a".repeat(64), null, null, count); }
}
