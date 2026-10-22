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
        for (var body : List.of(Map.<String, Object>of("path", "/private/model"), Map.<String, Object>of("model", source))) {
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

    @Test void registrationAndPollingExposeOnlyKnownImportPhasesWithSafeMessages() {
        var messages = Map.of(
                "DOWNLOADING", "다운로드를 요청했습니다",
                "VERIFYING", "모델 파일의 서명·크기·해시를 검사하고 있습니다",
                "COPYING", "AI 분석/추론 서버에 보관하고 보관한 파일을 다시 확인하고 있습니다",
                "REGISTERING", "모델 실행 환경에 새 모델을 등록하고 있습니다");
        for (var phase : messages.entrySet()) {
            var remote = new LinkedHashMap<>(imported("IMPORTING"));
            remote.put("phase", phase.getKey()); remote.put("message", "/private/model 원격 오류 원문");
            remote.put("path", "/private/model");
            when(client.inference(eq("POST"), eq("/api/models/trained/import"), anyMap(), eq(false))).thenReturn(remote);
            when(client.inference("GET", "/api/models/trained/imports/" + id, Map.of(), false)).thenReturn(remote);
            for (var result : List.of(service.register(id, Map.of()), service.importStatus(id))) {
                assertThat(result).containsEntry("status", "IMPORTING").containsEntry("phase", phase.getKey());
                assertThat(result.get("message").toString()).contains(phase.getValue());
                assertThat(result.toString()).doesNotContain("/private/model", "원격 오류 원문");
            }
        }
        verify(client, never()).inference(anyString(), contains("activate"), anyMap(), anyBoolean());
    }

    @Test void unknownOrMissingImportPhasesKeepTheGenericProgressMessage() {
        when(client.inference("GET", "/api/models/trained/imports/" + id, Map.of(), false)).thenReturn(imported("IMPORTING"));
        var generic = service.importStatus(id);
        assertThat(generic).containsEntry("status", "IMPORTING").doesNotContainKey("phase");
        var expectedMessage = generic.get("message");
        for (var phase : List.of("", "PENDING", "/private/model", Map.of("path", "/private/model"), 42)) {
            var remote = new LinkedHashMap<>(imported("IMPORTING"));
            remote.put("phase", phase); remote.put("message", "/private/model");
            when(client.inference("GET", "/api/models/trained/imports/" + id, Map.of(), false)).thenReturn(remote);
            assertThat(service.importStatus(id)).containsEntry("status", "IMPORTING").containsEntry("message", expectedMessage)
                    .doesNotContainKey("phase");
        }
    }

    @Test void completedOrFailedImportsDoNotExposeAStaleImportPhase() {
        for (var status : List.of("READY", "WAITING", "UNAVAILABLE", "NOT_STARTED")) {
            var remote = new LinkedHashMap<>(imported(status)); remote.put("phase", "DOWNLOADING");
            when(client.inference("GET", "/api/models/trained/imports/" + id, Map.of(), false)).thenReturn(remote);
            assertThat(service.importStatus(id)).containsEntry("status", status).doesNotContainKey("phase");
        }
    }

    @Test void importFailuresExplainTheRegistrationStageWithoutExposingRemoteDetails() {
        var messages = Map.of(
                "TRAINED_MODEL_IMPORT_TIMEOUT", "모델 등록 제한 시간이 지났습니다",
                "TRAINED_MODEL_CLOUD_DOWNLOAD_FAILED", "공유 모델 파일의 다운로드를 완료하지 못했습니다",
                "TRAINED_MODEL_CLOUD_DOWNLOAD_UNAVAILABLE", "공유 모델 파일의 다운로드를 완료하지 못했습니다",
                "TRAINED_MODEL_IMPORT_INTERRUPTED", "모델 등록이 중단됐습니다",
                "TRAINED_MODEL_DISK_FULL", "저장 공간이 부족합니다",
                "TRAINED_MODEL_INTEGRITY_FAILED", "파일의 서명·크기·해시를 확인하지 못했습니다");
        for (var error : messages.entrySet()) {
            var remote = new LinkedHashMap<>(imported("UNAVAILABLE"));
            remote.put("errorCode", error.getKey()); remote.put("message", "/private/model 원격 오류 원문");
            when(client.inference("GET", "/api/models/trained/imports/" + id, Map.of(), false)).thenReturn(remote);
            var result = service.importStatus(id);
            assertThat(result).containsEntry("status", "UNAVAILABLE").containsEntry("errorCode", error.getKey());
            assertThat(result.get("message").toString()).contains(error.getValue());
            assertThat(result.toString()).doesNotContain("/private/model", "원격 오류 원문", "학습 서버", "MLX");
        }
        when(client.inference(eq("POST"), eq("/api/models/trained/import"), anyMap(), eq(false)))
                .thenThrow(new ModelExecutionClient.RemoteFailure("TRAINED_MODEL_IMPORT_BUSY", false, 409));
        var rejected = service.register(id, Map.of());
        assertThat(rejected).containsEntry("status", "UNAVAILABLE");
        assertThat(rejected.get("message").toString()).contains("다른 모델을 등록 중입니다");
    }

    @Test void unknownImportFailureCodesStillExplainTheInferenceServerInsteadOfTrainingSetup() {
        for (var code : List.of("OTHER_REMOTE_FAILURE", "/private/error")) {
            var remote = new LinkedHashMap<>(imported("UNAVAILABLE"));
            remote.put("errorCode", code); remote.put("message", "/private/model");
            when(client.inference("GET", "/api/models/trained/imports/" + id, Map.of(), false)).thenReturn(remote);
            var result = service.importStatus(id);
            assertThat(result.get("message").toString()).contains("AI 분석/추론 서버", "파일 동기화");
            assertThat(result.toString()).doesNotContain("/private", "학습 서버", "MLX");
        }
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
        for (var code : List.of("MODEL_BUILD_UNAVAILABLE", "MODEL_BUILD_DISK_SPACE", "MODEL_BUILD_BUSY")) {
            doThrow(new ModelExecutionClient.RemoteFailure(code, true, 503)).when(client).training("POST", buildPath(), Map.of());
            assertThat(service.build(id, "POST", Map.of())).containsEntry("status", "UNAVAILABLE")
                    .containsEntry("available", false).containsEntry("errorCode", code);
        }
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
