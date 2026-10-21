package com.doenaai.model.service;

import com.doenaai.model.repository.ModelOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 학습 원본과 완성 모델을 구분하고 브라우저가 경로나 실행 명령을 전달하지 못하게 검사한다. */
class TrainedModelManagementServiceTest {
    private final ModelOperationRepository repository = mock(ModelOperationRepository.class);
    private final ModelExecutionClient client = mock(ModelExecutionClient.class);
    private final TrainedModelManagementService service = new TrainedModelManagementService(repository, client);
    private final UUID id = UUID.randomUUID();
    private final String source = "qwen3.8:27b", base = "mlx-community/Qwen3.8-27B-4bit";
    private final String revision = "a".repeat(40), manifestHash = "b".repeat(64), artifactHash = "c".repeat(64), engineHash = "d".repeat(64);

    @BeforeEach void setup() {
        when(client.targetKey(true)).thenReturn("training-target");
        when(repository.find(id)).thenReturn(Optional.of(origin()));
        when(client.training(anyString(), eq(buildPath()), anyMap())).thenReturn(build("SUCCEEDED"));
        when(client.inference(eq("POST"), eq("/api/models/trained/import"), anyMap(), eq(false))).thenReturn(imported("READY"));
        when(client.inference(eq("GET"), eq("/api/models/trained/imports/" + id), anyMap(), eq(false))).thenReturn(imported("READY"));
    }

    @Test void requestsBuildOnTrainingServerAndExposesOnlySafeProgress() {
        var remote = new LinkedHashMap<>(build("FUSING"));
        remote.put("progress", 42); remote.put("path", "/private/model"); remote.put("message", "원격 원문");
        when(client.training("POST", buildPath(), Map.of())).thenReturn(remote);
        var result = service.build(id, "POST", Map.of());
        assertThat(result).containsEntry("status", "FUSING").containsEntry("progress", 42).containsEntry("sourceModel", source);
        assertThat(result.toString()).doesNotContain("/private/model", "원격 원문");
        verify(client).training("POST", buildPath(), Map.of());
        verify(client, never()).inference(anyString(), anyString(), anyMap(), anyBoolean());
        verify(repository, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test void pollingAndCancellationUseOnlyTheOriginalTrainingJob() {
        assertThat(service.build(id, "GET", Map.of())).containsEntry("status", "SUCCEEDED");
        when(client.training("POST", buildPath() + "/cancel", Map.of())).thenReturn(build("CANCELLED"));
        assertThat(service.cancel(id, Map.of())).containsEntry("status", "CANCELLED");
        verify(client).training("GET", buildPath(), Map.of());
        verify(client).training("POST", buildPath() + "/cancel", Map.of());
        verify(client, never()).training(eq("POST"), eq("/api/training/jobs"), anyMap());
        verify(client, never()).inference(anyString(), anyString(), anyMap(), anyBoolean());
    }

    @Test void registersValidatedMetadataAndKeepsActualEngineDigestSeparateFromPackageHash() {
        var result = service.register(id, Map.of());
        assertThat(result).containsEntry("status", "READY").containsEntry("digest", engineHash)
                .containsEntry("manifestSha256", manifestHash).containsEntry("trainedModel", true)
                .containsEntry("qualityEvaluated", false).containsEntry("provider", "ollama");
        verify(client).inference(eq("POST"), eq("/api/models/trained/import"), argThat(body -> body.equals(metadata())), eq(false));
        verify(client, never()).inference(anyString(), contains("activate"), anyMap(), anyBoolean());
        verify(client, never()).inference(anyString(), contains("probe"), anyMap(), anyBoolean());
    }

    @Test void incompleteBuildCannotReachTheInferenceServer() {
        for (var state : List.of("PENDING", "FUSING", "FAILED", "CANCELLED")) {
            when(client.training("GET", buildPath(), Map.of())).thenReturn(build(state));
            assertThat(service.register(id, Map.of())).containsEntry("status", "UNAVAILABLE").containsEntry("errorCode", "MODEL_BUILD_NOT_COMPLETE");
        }
        verify(client, never()).inference(anyString(), anyString(), anyMap(), anyBoolean());
    }

    @Test void changedTrainingIdentityOrMalformedArtifactNeverReachesTheInferenceServer() {
        var changes = Map.<String, Object>ofEntries(
                Map.entry("jobId", UUID.randomUUID().toString()), Map.entry("version", "trained-web-other"),
                Map.entry("model", "qwen3.8:27b"), Map.entry("sourceModel", "gemma4:26b"),
                Map.entry("baseModel", "other/model"), Map.entry("baseRevision", "f".repeat(40)),
                Map.entry("manifestSha256", "invalid"), Map.entry("artifactSha256", "invalid"),
                Map.entry("artifactSize", 65L * 1024 * 1024 * 1024), Map.entry("quantization", "F16"));
        for (var changed : changes.entrySet()) {
            var remoteMetadata = new LinkedHashMap<>(metadata()); remoteMetadata.put(changed.getKey(), changed.getValue());
            when(client.training("GET", buildPath(), Map.of())).thenReturn(Map.of("jobId", id.toString(), "status", "SUCCEEDED", "result", remoteMetadata));
            assertThat(service.register(id, Map.of())).containsEntry("status", "UNAVAILABLE").containsEntry("errorCode", "MODEL_BUILD_METADATA_INVALID");
        }
        verify(client, never()).inference(anyString(), anyString(), anyMap(), anyBoolean());
    }

    @Test void rejectedBrowserFieldsDoNotReachEitherRemoteServer() {
        for (var body : List.of(Map.<String, Object>of("path", "/private/model"), Map.<String, Object>of("model", source),
                Map.<String, Object>of("profile", genericProfile()))) {
            assertThatThrownBy(() -> service.build(id, "POST", body)).isInstanceOf(ModelCatalogException.class);
            assertThatThrownBy(() -> service.cancel(id, body)).isInstanceOf(ModelCatalogException.class);
            assertThatThrownBy(() -> service.register(id, body)).isInstanceOf(ModelCatalogException.class);
        }
        assertThatThrownBy(() -> service.register(id, null)).isInstanceOf(ModelCatalogException.class);
        verifyNoInteractions(client, repository);
    }

    @Test void absentOrForeignHistoryIsRejectedBeforeRemoteMutation() {
        when(repository.find(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.register(id, Map.of())).isInstanceOf(ModelCatalogException.class);
        for (var changes : List.of(Map.<String, Object>of("action", "MODEL_PROBE"), Map.<String, Object>of("targetKey", "other-server"))) {
            var row = new LinkedHashMap<>(origin()); row.putAll(changes);
            when(repository.find(id)).thenReturn(Optional.of(row));
            assertThatThrownBy(() -> service.build(id, "POST", Map.of())).isInstanceOf(ModelCatalogException.class);
        }
        verify(client, never()).training(anyString(), anyString(), anyMap());
        verify(client, never()).inference(anyString(), anyString(), anyMap(), anyBoolean());
    }

    @Test void savedBaseModelAndRevisionAreRequiredInsteadOfGuessingFromTheModelName() {
        for (var target : List.of(Map.<String, Object>of(), Map.<String, Object>of("sourceModel", source),
                Map.<String, Object>of("sourceModel", source, "baseModel", base))) {
            var row = new LinkedHashMap<>(origin()); row.put("result", Map.of("target", target));
            when(repository.find(id)).thenReturn(Optional.of(row));
            assertThatThrownBy(() -> service.register(id, Map.of())).isInstanceOf(ModelCatalogException.class);
        }
        verify(client, never()).training(anyString(), anyString(), anyMap());
        verify(client, never()).inference(anyString(), anyString(), anyMap(), anyBoolean());
    }

    @Test void changedImportManifestCannotBecomeReady() {
        var remote = new LinkedHashMap<>(imported("READY")); remote.put("manifestSha256", "e".repeat(64));
        when(client.inference(eq("POST"), eq("/api/models/trained/import"), anyMap(), eq(false))).thenReturn(remote);
        assertThat(service.register(id, Map.of())).containsEntry("status", "UNAVAILABLE")
                .containsEntry("errorCode", "MODEL_IMPORT_METADATA_INVALID").doesNotContainKey("digest");
    }

    @Test void importPollingKeepsInitialAndSynchronizationStatesWithoutClaimingActivation() {
        for (var state : List.of("NOT_STARTED", "IMPORTING", "WAITING")) {
            when(client.inference("GET", "/api/models/trained/imports/" + id, Map.of(), false)).thenReturn(imported(state));
            assertThat(service.importStatus(id)).containsEntry("status", state).containsEntry("model", runtimeModel());
        }
        verify(client, never()).inference(eq("POST"), anyString(), anyMap(), anyBoolean());
    }

    @Test void malformedImportResponsesAreSanitizedAndCannotBecomeReady() {
        for (var changes : List.of(Map.<String, Object>of("jobId", UUID.randomUUID().toString()),
                Map.<String, Object>of("model", "https://private/model"), Map.<String, Object>of("sourceModel", "gemma4:26b"),
                Map.<String, Object>of("digest", "invalid"), Map.<String, Object>of("status", "APPLIED"))) {
            var remote = new LinkedHashMap<>(imported("READY")); remote.putAll(changes);
            remote.put("message", "/private/value");
            when(client.inference("GET", "/api/models/trained/imports/" + id, Map.of(), false)).thenReturn(remote);
            var result = service.importStatus(id);
            assertThat(result).containsEntry("status", "UNAVAILABLE").doesNotContainKey("digest");
            assertThat(result.toString()).doesNotContain("https://private", "/private/value");
        }
    }

    @Test void uncertainRemoteRequestsStayUnknownAndDeterministicFailuresStayUnavailable() {
        when(client.training("POST", buildPath(), Map.of())).thenThrow(new ModelExecutionClient.RemoteFailure("REMOTE_UNREACHABLE", true, 503));
        assertThat(service.build(id, "POST", Map.of())).containsEntry("status", "UNKNOWN").containsEntry("errorCode", "REMOTE_UNREACHABLE");
        when(client.inference(eq("POST"), eq("/api/models/trained/import"), anyMap(), eq(false)))
                .thenThrow(new ModelExecutionClient.RemoteFailure("REMOTE_REJECTED", false, 409));
        assertThat(service.register(id, Map.of())).containsEntry("status", "UNAVAILABLE").containsEntry("errorCode", "REMOTE_REJECTED");
        when(client.inference("GET", "/api/models/trained/imports/" + id, Map.of(), false))
                .thenThrow(new ModelExecutionClient.RemoteFailure("REMOTE_UNREACHABLE", true, 503));
        assertThat(service.importStatus(id)).containsEntry("status", "UNKNOWN").doesNotContainKey("digest");
    }

    @Test void knownBuildPreconditionsAreUnavailableAndExplainTheRequiredTrainingServerSetup() {
        var remote = new LinkedHashMap<>(build("NOT_BUILT"));
        remote.put("available", false); remote.put("reasonCode", "MODEL_BUILD_DISK_SPACE");
        when(client.training("GET", buildPath(), Map.of())).thenReturn(remote);
        var waiting = service.build(id, "GET", Map.of());
        assertThat(waiting).containsEntry("available", false).containsEntry("status", "NOT_BUILT");
        assertThat(waiting.get("message").toString()).contains("학습 서버", "180 GiB");
        for (var code : List.of("MODEL_BUILD_UNAVAILABLE", "MODEL_BUILD_DISK_SPACE", "MODEL_BUILD_BUSY", "MODEL_BUILD_UNSUPPORTED_PROFILE")) {
            doThrow(new ModelExecutionClient.RemoteFailure(code, true, 503)).when(client).training("POST", buildPath(), Map.of());
            assertThat(service.build(id, "POST", Map.of())).containsEntry("status", "UNAVAILABLE")
                    .containsEntry("available", false).containsEntry("errorCode", code);
        }
    }

    @Test void formatTwoPreservesTheSignedProfileOnlyInTheInternalImportRequest() {
        when(client.training("GET", buildPath(), Map.of())).thenReturn(Map.of("jobId", id.toString(), "status", "SUCCEEDED", "result", genericMetadata()));
        var response = service.build(id, "GET", Map.of());
        assertThat(((Map<?, ?>) response.get("result")).get("formatVersion")).isEqualTo(2);
        assertThat(((Map<?, ?>) response.get("result")).containsKey("profile")).isFalse();
        assertThat(service.register(id, Map.of())).containsEntry("status", "READY");
        verify(client).inference(eq("POST"), eq("/api/models/trained/import"), argThat(body -> body.equals(genericMetadata())), eq(false));
    }

    @Test void invalidGenericProfilesCannotReachTheInferenceServer() {
        var invalid = new java.util.ArrayList<Map<String, Object>>();
        for (var changed : Map.<String, Object>ofEntries(
                Map.entry("modelType", "qwen3"), Map.entry("loraLayers", 0), Map.entry("loraRank", 2.5),
                Map.entry("loraScale", Double.NaN), Map.entry("loraDropout", 1), Map.entry("maxSequenceLength", 32769),
                Map.entry("batchSize", 5), Map.entry("learningRate", 0.1), Map.entry("gradientCheckpointing", "true"),
                Map.entry("loraKeys", List.of("mlp.up_proj", "mlp.up_proj")), Map.entry("exporter", "../../command"),
                Map.entry("exportArchitecture", "qwen3"), Map.entry("path", "/private/model")).entrySet()) {
            var profile = new LinkedHashMap<>(genericProfile()); profile.put(changed.getKey(), changed.getValue()); invalid.add(profile);
        }
        for (var profile : invalid) {
            var supplied = new LinkedHashMap<>(genericMetadata()); supplied.put("profile", profile);
            when(client.training("GET", buildPath(), Map.of())).thenReturn(Map.of("jobId", id.toString(), "status", "SUCCEEDED", "result", supplied));
            assertThat(service.register(id, Map.of())).containsEntry("status", "UNAVAILABLE").containsEntry("errorCode", "MODEL_BUILD_METADATA_INVALID");
        }
        verify(client, never()).inference(anyString(), anyString(), anyMap(), anyBoolean());
    }

    @Test void malformedFormatTwoIdentityAndUnexpectedFieldsAreRejected() {
        for (var change : Map.<String, Object>of("formatVersion", 2.0, "architecture", "qwen3", "modelType", "unsupported",
                "command", "ignored-command").entrySet()) {
            var supplied = new LinkedHashMap<>(genericMetadata()); supplied.put(change.getKey(), change.getValue());
            when(client.training("GET", buildPath(), Map.of())).thenReturn(Map.of("jobId", id.toString(), "status", "SUCCEEDED", "result", supplied));
            assertThat(service.register(id, Map.of())).containsEntry("errorCode", "MODEL_BUILD_METADATA_INVALID");
        }
        var supplied = new LinkedHashMap<>(genericMetadata()); supplied.remove("profile");
        when(client.training("GET", buildPath(), Map.of())).thenReturn(Map.of("jobId", id.toString(), "status", "SUCCEEDED", "result", supplied));
        assertThat(service.register(id, Map.of())).containsEntry("errorCode", "MODEL_BUILD_METADATA_INVALID");
        verify(client, never()).inference(anyString(), anyString(), anyMap(), anyBoolean());
    }

    private Map<String, Object> genericProfile() {
        return Map.ofEntries(Map.entry("modelType", "llama"), Map.entry("loraLayers", 4), Map.entry("loraRank", 8),
                Map.entry("loraScale", 16.0), Map.entry("loraDropout", 0.0), Map.entry("loraKeys", List.of("self_attn.q_proj", "self_attn.v_proj")),
                Map.entry("maxSequenceLength", 2048), Map.entry("batchSize", 1), Map.entry("learningRate", 1e-5),
                Map.entry("gradientCheckpointing", true), Map.entry("exporter", "mlx-lm-hf-gguf-v1"), Map.entry("exportArchitecture", "llama"));
    }

    private Map<String, Object> genericMetadata() {
        var value = new LinkedHashMap<>(metadata());
        value.put("formatVersion", 2); value.put("modelType", "llama"); value.put("architecture", "llama"); value.put("profile", genericProfile());
        return value;
    }

    private Map<String, Object> origin() {
        return Map.of("action", "TRAINING_START", "targetKey", "training-target", "status", "SUCCEEDED", "request", Map.of("model", source),
                "result", Map.of("target", Map.of("sourceModel", source, "baseModel", base, "revision", revision)));
    }
    private Map<String, Object> metadata() {
        return Map.of("jobId", id.toString(), "version", "trained-web-" + id, "model", runtimeModel(), "sourceModel", source,
                "baseModel", base, "baseRevision", revision, "manifestSha256", manifestHash, "artifactSha256", artifactHash,
                "artifactSize", 17L * 1024 * 1024 * 1024, "quantization", "Q4_K_M");
    }
    private Map<String, Object> build(String status) { return Map.of("jobId", id.toString(), "status", status, "result", metadata()); }
    private Map<String, Object> imported(String status) {
        return Map.of("jobId", id.toString(), "status", status, "model", runtimeModel(), "sourceModel", source, "manifestSha256", manifestHash, "digest", engineHash);
    }
    private String buildPath() { return "/api/training/jobs/" + id + "/model-build"; }
    private String runtimeModel() { return "doenaai-trained:web-" + id; }
}
