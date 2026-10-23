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
    private final ModelHistoryService history = mock(ModelHistoryService.class);
    private final ModelExecutionService service = new ModelExecutionService(repository, client, history);
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

    @Test void selectingAnAdapterPreservesItsExactIdentityForTraining() {
        String adapter = UUID.randomUUID().toString();
        String name = "doenaai-mlx:web-" + adapter;
        when(client.inference("GET", "/api/models/installed", Map.of(), false)).thenReturn(Map.of("model", name,
                "provider", "mlx", "adapterId", adapter, "sourceModel", "qwen3.8:27b",
                "models", List.of(Map.of("name", name, "provider", "mlx", "adapterId", adapter, "sourceModel", "qwen3.8:27b"))));
        when(client.training("GET", "/api/training/capabilities?model=" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8), Map.of())).thenReturn(Map.of("trainingExecution", false));
        when(client.training("GET", "/api/training/jobs", Map.of())).thenReturn(Map.of("jobs", List.of()));
        assertThat(service.training(name)).containsEntry("requestedModel", name).containsEntry("selectedModel", name);
        assertThat(service.training()).containsEntry("requestedModel", name);
        verify(client, never()).training("GET", "/api/training/capabilities?model=qwen3.8%3A27b", Map.of());
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

    @Test void trainedModelSelectionKeepsTheChosenIdentityAndSeparatesItsDatasetSource() {
        String trainingId = UUID.randomUUID().toString();
        String name = "doenaai-trained:web-" + trainingId;
        String source = "qwen3.8:27b";
        var identity = Map.<String, Object>of("name", name, "provider", "ollama", "trainedModel", true,
                "trainingJobId", trainingId, "sourceModel", "qwen3.8:27b");
        when(client.inference("GET", "/api/models/installed", Map.of(), false)).thenReturn(Map.of("model", name,
                "provider", "ollama", "trainedModel", true, "trainingJobId", trainingId, "sourceModel", "qwen3.8:27b", "models", List.of(identity)));
        var target = new LinkedHashMap<String, Object>(Map.of("sourceModel", source, "baseModel", "example/base",
                "revision", "a".repeat(40), "digest", digest));
        target.put("selectedModel", name); target.put("resumeJobId", trainingId);
        target.put("parentAdapterSha256", "b".repeat(64)); target.put("parentConfigSha256", "c".repeat(64));
        when(client.training("GET", "/api/training/capabilities?model=" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8), Map.of()))
                .thenReturn(Map.of("trainingExecution", true, "dataset", Map.of("target", target, "samples", List.of(Map.of("caseId", "approved-case", "sourceModel", source)))));
        when(client.training("GET", "/api/training/jobs", Map.of())).thenReturn(Map.of("jobs", List.of()));
        var catalog = service.training(name);
        assertThat(catalog).containsEntry("requestedModel", name).containsEntry("selectedModel", name);
        assertThat(catalog.get("dataset").toString()).contains("approved-case", "sourceModel=qwen3.8:27b", "selectedModel=" + name, "parentConfigSha256=");
        assertThat(service.training()).containsEntry("requestedModel", name);
        when(client.training(eq("POST"), eq("/api/training/jobs"), anyMap())).thenReturn(Map.of("status", "PENDING", "target", target));
        assertThat(service.execute("TRAINING_START", new ModelExecutionRequest(id, name, digest, null, null, 10), actor, null))
                .containsEntry("status", "PENDING").containsEntry("selectedModel", name).containsEntry("sourceModel", source);
        verify(client).training(eq("POST"), eq("/api/training/jobs"), argThat(body -> name.equals(body.get("model"))));
        verify(client, never()).training("GET", "/api/training/capabilities?model=qwen3.8%3A27b", Map.of());
    }

    @Test void trainingProfilesAndExportSupportExposeOnlySafeFields() {
        var profile = new LinkedHashMap<String, Object>(Map.of("trainer", "mlx-lora-v1", "modelType", "gemma3",
                "loraLayers", 4, "loraRank", 8, "maxSequenceLength", 2048, "batchSize", 1, "continuation", false));
        profile.put("directory", "/private/model"); profile.put("command", "sensitive-command");
        var export = Map.<String, Object>of("supported", false, "reasonCode", "MODEL_BUILD_UNSUPPORTED_PROFILE",
                "message", "/private/details", "architecture", "gemma3", "path", "/private/model");
        when(client.training("GET", "/api/training/capabilities?model=gemma4%3A26b", Map.of()))
                .thenReturn(Map.of("trainingExecution", false, "trainingProfile", profile, "modelExport", export));
        when(client.training("GET", "/api/training/jobs", Map.of())).thenReturn(Map.of("jobs", List.of()));
        var value = service.training("gemma4:26b");
        assertThat(value.get("trainingProfile").toString()).contains("mlx-lora-v1", "loraRank=8", "maxSequenceLength=2048");
        assertThat(value.get("modelExport").toString()).contains("supported=false", "MODEL_BUILD_UNSUPPORTED_PROFILE");
        assertThat(value.toString()).doesNotContain("/private/", "sensitive-command", "directory", "command");
    }

    @Test void aDerivedSelectionCannotSilentlyUseAnOriginalTargetFromAnOlderServer() {
        String name = "doenaai-trained:web-" + UUID.randomUUID();
        when(client.inference("GET", "/api/models/installed", Map.of(), false)).thenReturn(Map.of("models", List.of(Map.of("name", name))));
        var original = Map.of("sourceModel", "qwen3.8:27b", "baseModel", "example/base", "revision", "a".repeat(40), "digest", digest);
        when(client.training("GET", "/api/training/capabilities?model=" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8), Map.of()))
                .thenReturn(Map.of("trainingExecution", true, "dataset", Map.of("target", original)));
        when(client.training("GET", "/api/training/jobs", Map.of())).thenReturn(Map.of("jobs", List.of()));
        var value = service.training(name);
        assertThat((Map<?, ?>) value.get("dataset")).isEmpty();
        assertThat(((Map<?, ?>) value.get("capabilities")).get("trainingExecution")).isEqualTo(false);
    }

    @Test void installedTrainedModelMetadataPreservesJobIdentityWithoutClaimingQualityOrExposingPaths() {
        String trainingId = UUID.randomUUID().toString();
        String name = "doenaai-trained:web-" + trainingId;
        var identity = Map.<String, Object>of("name", name, "provider", "ollama", "trainedModel", true,
                "trainingJobId", trainingId, "sourceModel", "qwen3.8:27b", "qualityEvaluated", true, "path", "/private/model");
        when(client.inference("GET", "/api/models/installed", Map.of(), false)).thenReturn(Map.of("model", name,
                "provider", "ollama", "trainedModel", true, "trainingJobId", trainingId, "sourceModel", "qwen3.8:27b", "models", List.of(identity)));
        var result = service.installed();
        assertThat(result).containsEntry("provider", "ollama").containsEntry("trainedModel", true)
                .containsEntry("trainingJobId", trainingId).containsEntry("sourceModel", "qwen3.8:27b").containsEntry("qualityEvaluated", false);
        assertThat(result.toString()).doesNotContain("/private/model", "qualityEvaluated=true");
    }

    @Test void operationPagesOnlyReadTheRequestedRowsAndPreservePaginationMetadata() {
        var row = operationRow(id, "TRAINING_START", "SUCCEEDED", "training-target");
        when(repository.page(4, 10, "Qwen 학습", "SUCCEEDED"))
                .thenReturn(new ModelOperationRepository.Page(List.of(row), 4, 10, 137));
        var result = service.operations(4, 10, "Ｑｗｅｎ 학습", "SUCCEEDED");
        assertThat(result).containsEntry("page", 4).containsEntry("pageSize", 10).containsEntry("totalCount", 137L)
                .containsEntry("totalPages", 14L).containsEntry("hasNext", true).containsEntry("hasPrev", true);
        assertThat((List<?>) result.get("operations")).hasSize(1);
        verify(repository).page(4, 10, "Qwen 학습", "SUCCEEDED");
        verify(repository, never()).recent(anyInt());
        verifyNoInteractions(history);
    }

    @Test void invalidPageSizeStatusOrSearchNeverReachesStorageOrRemoteServers() {
        for (int page : new int[]{-1, Integer.MIN_VALUE})
            assertThatThrownBy(() -> service.operations(page, 10, "", "")).isInstanceOf(ModelCatalogException.class);
        for (int size : new int[]{0, -1, 51, Integer.MAX_VALUE})
            assertThatThrownBy(() -> service.operations(0, size, "", "")).isInstanceOf(ModelCatalogException.class);
        for (String query : List.of("a".repeat(201), "qwen\n학습", "qwen" + (char) 0, "qwen" + (char) 127, "qwen" + (char) 0x202e))
            assertThatThrownBy(() -> service.operations(0, 10, query, "")).isInstanceOf(ModelCatalogException.class);
        assertThatThrownBy(() -> service.operations(0, 10, "", "ANY")) .isInstanceOf(ModelCatalogException.class);
        verifyNoInteractions(repository, client, history);
    }

    @Test void pageRefreshUsesAtMostThreeReadOnlyCallsForCurrentServerProgressRows() {
        var rows = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < 4; index++) rows.add(operationRow(UUID.randomUUID(), "TRAINING_START", "TRAINING", "training-target"));
        rows.add(operationRow(UUID.randomUUID(), "MODEL_ACTIVATE", "UNKNOWN", "foreign-target"));
        when(repository.page(0, 10, "", "")).thenReturn(new ModelOperationRepository.Page(rows, 0, 10, 5));
        when(client.training(eq("GET"), startsWith("/api/training/jobs/"), anyMap())).thenReturn(Map.of("status", "TRAINING", "progress", 7));
        assertThat((List<?>) service.operations().get("operations")).hasSize(5);
        verify(client, times(3)).training(eq("GET"), startsWith("/api/training/jobs/"), eq(Map.of()));
        verify(client, never()).training(eq("POST"), anyString(), anyMap());
        verify(client, never()).inference(anyString(), anyString(), anyMap(), anyBoolean());
        verify(repository, times(2)).page(0, 10, "", "");
    }

    @Test void changedTrainingStatusRecountsTheFilteredPageAndClampsItsLastPage() {
        var row = operationRow(id, "TRAINING_START", "TRAINING", "training-target");
        when(repository.page(2, 10, "qwen", "TRAINING"))
                .thenReturn(new ModelOperationRepository.Page(List.of(row), 2, 10, 21),
                        new ModelOperationRepository.Page(List.of(), 1, 10, 20));
        when(client.training("GET", "/api/training/jobs/" + id, Map.of())).thenReturn(Map.of("status", "SUCCEEDED"));
        var result = service.operations(2, 10, "qwen", "TRAINING");
        assertThat(result).containsEntry("page", 1).containsEntry("totalCount", 20L).containsEntry("hasNext", false);
        verify(repository, times(2)).page(2, 10, "qwen", "TRAINING");
        verify(client).training("GET", "/api/training/jobs/" + id, Map.of());
        verify(client, never()).training(eq("POST"), anyString(), anyMap());
    }

    @Test void unknownStateIsAnAllowedFilterEvenWhenNoMatchingRowsExist() {
        when(repository.page(0, 10, "", "UNKNOWN")).thenReturn(new ModelOperationRepository.Page(List.of(), 0, 10, 0));
        assertThat(service.operations(0, 10, "", "UNKNOWN")).containsEntry("totalCount", 0L);
        verify(repository).page(0, 10, "", "UNKNOWN");
    }

    @Test void unchangedStatusWithChangedModelMetadataStillRechecksSearchResults() {
        var row = operationRow(id, "TRAINING_START", "TRAINING", "training-target");
        when(repository.page(0, 10, "qwen", "TRAINING"))
                .thenReturn(new ModelOperationRepository.Page(List.of(row), 0, 10, 1),
                        new ModelOperationRepository.Page(List.of(), 0, 10, 0));
        when(client.training("GET", "/api/training/jobs/" + id, Map.of())).thenReturn(Map.of("status", "TRAINING", "model", "gemma4:26b"));
        assertThat(service.operations(0, 10, "qwen", "TRAINING")).containsEntry("totalCount", 0L);
        verify(repository, times(2)).page(0, 10, "qwen", "TRAINING");
    }

    @Test void appliedAndRolledBackResultsRecordHistoryWithoutChangingTheirSuccessOnStorageFailure() {
        for (var entry : Map.of("MODEL_ACTIVATE", "APPLIED", "MODEL_ROLLBACK", "ROLLED_BACK").entrySet()) {
            var row = operationRow(id, entry.getKey(), entry.getValue(), "inference-target");
            doThrow(new IllegalStateException("/private/history-error")).when(history).deployment(row);
            var result = service.operation(id);
            assertThat(result).containsEntry("status", entry.getValue()).containsEntry("historySyncRequired", true);
            assertThat(result.get("message").toString()).contains("기록 동기화");
            assertThat(result.toString()).doesNotContain("/private/history-error");
            verify(history).deployment(row);
        }
        verify(client, never()).inference(anyString(), anyString(), anyMap(), anyBoolean());
    }

    @Test void foreignServerCompletedResultsDoNotCreateLocalDeploymentHistory() {
        operationRow(id, "MODEL_ACTIVATE", "APPLIED", "foreign-target");
        assertThat(service.operation(id)).containsEntry("status", "APPLIED").doesNotContainKey("historySyncRequired");
        verifyNoInteractions(history);
    }

    @Test void operationListUsesBoundedHistoryRecordingWhileDetailCanRecoverRegistration() {
        var row = operationRow(id, "MODEL_ACTIVATE", "APPLIED", "inference-target");
        when(repository.page(0, 10, "", "")).thenReturn(new ModelOperationRepository.Page(List.of(row), 0, 10, 1));
        doThrow(new IllegalStateException("등록 이력 보완 필요")).when(history).deploymentFromList(row);
        var listed = (Map<?, ?>) ((List<?>) service.operations().get("operations")).getFirst();
        assertThat(listed.get("status")).isEqualTo("APPLIED");
        assertThat(listed.get("historySyncRequired")).isEqualTo(true);
        verify(history).deploymentFromList(row);
        verify(history, never()).deployment(anyMap());
        assertThat(service.operation(id)).containsEntry("status", "APPLIED").doesNotContainKey("historySyncRequired");
        verify(history).deployment(row);
        verify(client, never()).inference(anyString(), anyString(), anyMap(), anyBoolean());
    }

    private Map<String, Object> operationRow(UUID key, String action, String status, String target) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", key.toString()); row.put("actorId", actor.toString()); row.put("action", action);
        row.put("status", status); row.put("targetKey", target); row.put("request", Map.of()); row.put("result", Map.of());
        records.put(key, row);
        return row;
    }

    private static ModelExecutionRequest training(UUID id, int count) { return new ModelExecutionRequest(id, "qwen3.8:27b", "a".repeat(64), null, null, count); }
}
