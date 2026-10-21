package com.doenaai.model.service;

import com.doenaai.model.repository.ModelOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdapterManagementServiceTest {
    private final ModelOperationRepository repository = mock(ModelOperationRepository.class);
    private final ModelExecutionClient client = mock(ModelExecutionClient.class);
    private final AdapterManagementService service = new AdapterManagementService(repository, client);
    private final UUID id = UUID.randomUUID();
    private final String source = "qwen3.8:27b";
    private final String base = "mlx-community/Qwen3.8-27B-4bit";
    private final String revision = "a".repeat(40);
    private final String hash = "b".repeat(64);

    @BeforeEach void setup() {
        when(client.targetKey(true)).thenReturn("training-target");
        when(repository.find(id)).thenReturn(Optional.of(Map.of("action", "TRAINING_START", "targetKey", "training-target", "status", "SUCCEEDED",
                "request", Map.of("model", source), "result", Map.of("target", Map.of("sourceModel", source, "baseModel", base, "revision", revision)))));
        when(client.training("POST", "/api/training/jobs/" + id + "/delivery", Map.of())).thenReturn(delivery());
        when(client.inference(eq("POST"), eq("/api/models/adapters/import"), anyMap(), eq(false))).thenReturn(imported("READY"));
    }

    @Test void preparesPublishedAdapterUsingOnlyTheValidatedMetadataWithoutInferenceOrActivation() {
        var result = service.prepare(id);
        assertThat(result).containsEntry("status", "READY").containsEntry("model", "doenaai-mlx:web-" + id)
                .containsEntry("sourceModel", source).containsEntry("qualityEvaluated", false);
        verify(client).inference(eq("POST"), eq("/api/models/adapters/import"), argThat(body -> body.size() == 6
                && body.get("jobId").equals(id.toString()) && body.get("manifestSha256").equals(hash)
                && !body.containsKey("path") && !body.containsKey("status")), eq(false));
        verify(repository, never()).create(any(), any(), any(), any(), any(), any());
        verify(repository, never()).record(any(), any(), any(), any());
        assertThat(result.toString()).doesNotContain("/secret", "http://", "임의 원문");
    }

    @Test void repeatedPreparationUsesTheSameJobAndDoesNotCreateANewTrainingRun() {
        service.prepare(id); service.prepare(id);
        verify(client, times(2)).training("POST", "/api/training/jobs/" + id + "/delivery", Map.of());
        verify(client, never()).training(eq("POST"), eq("/api/training/jobs"), anyMap());
    }

    @Test void unverifiedOrMismatchedDeliveryNeverReachesTheInferenceServer() {
        for (var replacement : Map.of("manifestSha256", "unsafe", "sourceModel", "gemma4:26b", "baseRevision", "c".repeat(40), "jobId", UUID.randomUUID().toString(), "version", "web-other").entrySet()) {
            var supplied = new LinkedHashMap<>(delivery()); supplied.put(replacement.getKey(), replacement.getValue());
            when(client.training("POST", "/api/training/jobs/" + id + "/delivery", Map.of())).thenReturn(supplied);
            assertThat(service.prepare(id)).containsEntry("status", "UNAVAILABLE").containsEntry("errorCode", "ADAPTER_METADATA_INVALID");
        }
        verify(client, never()).inference(any(), any(), any(), anyBoolean());
    }

    @Test void missingOrForeignTrainingRecordStopsBeforeAnyRemoteMutation() {
        when(repository.find(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.prepare(id)).isInstanceOf(ModelCatalogException.class);
        when(repository.find(id)).thenReturn(Optional.of(Map.of("action", "TRAINING_START", "targetKey", "foreign")));
        assertThatThrownBy(() -> service.prepare(id)).isInstanceOf(ModelCatalogException.class);
        verify(client, never()).training(any(), any(), anyMap());
        verify(client, never()).inference(any(), any(), anyMap(), anyBoolean());
    }

    @Test void delayedSynchronizationRemainsWaitingAndDoesNotClaimActivation() {
        when(client.inference(eq("POST"), eq("/api/models/adapters/import"), anyMap(), eq(false))).thenReturn(imported("WAITING"));
        assertThat(service.prepare(id)).containsEntry("status", "WAITING");
    }

    @Test void interruptedPublishOrImportRemainsUnknownWithoutLeakingRemoteMessages() {
        when(client.inference(eq("POST"), eq("/api/models/adapters/import"), anyMap(), eq(false)))
                .thenThrow(new ModelExecutionClient.RemoteFailure("REMOTE_UNREACHABLE", true, 503));
        assertThat(service.prepare(id)).containsEntry("status", "UNKNOWN").doesNotContainKey("digest");
        when(client.training("POST", "/api/training/jobs/" + id + "/delivery", Map.of()))
                .thenThrow(new ModelExecutionClient.RemoteFailure("REMOTE_UNREACHABLE", true, 503));
        assertThat(service.prepare(id)).containsEntry("status", "UNKNOWN");
    }

    @Test void anotherValidManifestDigestCannotBePreparedAsThePublishedAdapter() {
        var response = new LinkedHashMap<>(imported("READY"));
        response.put("digest", "c".repeat(64));
        when(client.inference(eq("POST"), eq("/api/models/adapters/import"), anyMap(), eq(false))).thenReturn(response);
        assertThat(service.prepare(id)).containsEntry("status", "UNAVAILABLE")
                .containsEntry("errorCode", "ADAPTER_IMPORT_RESPONSE_INVALID").doesNotContainKey("digest");
    }

    @Test void malformedImportResponseNeverBecomesReady() {
        when(client.inference(eq("POST"), eq("/api/models/adapters/import"), anyMap(), eq(false)))
                .thenReturn(Map.of("status", "READY", "model", "https://secret", "message", "/secret/model"));
        var result = service.prepare(id);
        assertThat(result).containsEntry("status", "UNAVAILABLE").containsEntry("errorCode", "ADAPTER_IMPORT_RESPONSE_INVALID");
        assertThat(result.toString()).doesNotContain("https://secret", "/secret/model");
    }

    private Map<String, Object> delivery() {
        return Map.of("status", "PUBLISHED", "jobId", id.toString(), "version", "web-" + id,
                "manifestSha256", hash, "sourceModel", source, "baseModel", base, "baseRevision", revision,
                "path", "/secret", "message", "임의 원문");
    }
    private Map<String, Object> imported(String status) {
        return Map.of("status", status, "model", "doenaai-mlx:web-" + id, "digest", hash,
                "sourceModel", source, "adapterId", id.toString(), "message", "임의 원문");
    }
}
