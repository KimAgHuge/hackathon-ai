package com.doenaai.model.service;

import com.doenaai.model.repository.ModelHistoryRepository;
import com.doenaai.model.repository.ModelHistoryRepository.Deployment;
import com.doenaai.model.repository.ModelHistoryRepository.Version;
import com.doenaai.model.repository.ModelOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 완료 상태의 증거와 원본 식별값을 검사하며 실제 모델 전환은 호출하지 않는다. */
class ModelHistoryServiceTest {
    private final ModelHistoryRepository repository = mock(ModelHistoryRepository.class);
    private final ModelOperationRepository operations = mock(ModelOperationRepository.class);
    private final ModelExecutionClient client = mock(ModelExecutionClient.class);
    private final ModelHistoryService service = new ModelHistoryService(repository, operations, client);
    private final UUID job = UUID.randomUUID(), actor = UUID.randomUUID(), operationId = UUID.randomUUID();
    private final String source = "qwen3.8:27b", base = "mlx-community/Qwen3.8-27B-4bit";
    private final String model = "doenaai-trained:web-" + job, digest = "d".repeat(64), artifact = "a".repeat(64);
    private final Instant confirmedAt = Instant.parse("2026-10-22T06:00:00Z");

    @BeforeEach void setup() {
        when(client.targetKey(true)).thenReturn("training-target");
        when(client.targetKey(false)).thenReturn("inference-target");
        when(client.inferenceServerKey()).thenReturn("f".repeat(64));
    }

    @Test void verifiedRegistrationCreatesMergedVersionWithOriginalActorAndSeparateEngineAndArtifactHashes() {
        var ready = new LinkedHashMap<>(ready()); ready.put("privatePath", "/private/model"); ready.put("message", "원격 원문");
        service.registration(origin(), ready);
        var saved = ArgumentCaptor.forClass(Version.class);
        verify(repository).registration(saved.capture());
        assertThat(saved.getValue()).satisfies(version -> {
            assertThat(version.model()).isEqualTo(model);
            assertThat(version.version()).isEqualTo("trained-web-" + job);
            assertThat(version.artifactType()).isEqualTo("MERGED");
            assertThat(version.checksum()).isEqualTo(artifact);
            assertThat(version.actorId()).isEqualTo(actor);
            assertThat(version.metadata()).containsEntry("trainingJobId", job.toString())
                    .containsEntry("engineDigest", digest).containsEntry("registrationVerified", true)
                    .containsEntry("qualityEvaluated", false).containsKey("registeredObservedAt")
                    .doesNotContainKeys("privatePath", "message");
        });
        verifyNoInteractions(operations);
        verify(client, never()).inference(anyString(), anyString(), anyMap(), anyBoolean());
    }

    @Test void incompleteRegistrationAndFailedOrUncertainExecutionNeverCreateCatalogHistory() {
        service.registration(Map.of(), Map.of("status", "IMPORTING"));
        for (String status : List.of("FAILED", "UNKNOWN", "PENDING", "PASSED")) {
            var row = operation(source, false); row.put("status", status);
            service.deployment(row);
        }
        verifyNoInteractions(repository, operations);
    }

    @Test void continuedTrainingUsesItsOriginalDatasetSourceWhileKeepingTheSelectedModelSeparate() {
        var origin = new LinkedHashMap<>(origin());
        origin.put("result", Map.of("target", Map.of("selectedModel", "doenaai-trained:web-" + UUID.randomUUID(),
                "sourceModel", source, "baseModel", base, "revision", "b".repeat(40))));
        service.registration(origin, ready());
        verify(repository).registration(argThat(version -> source.equals(version.metadata().get("sourceModel"))
                && base.equals(version.baseModel()) && job.toString().equals(version.metadata().get("trainingJobId"))));
    }

    @Test void mismatchedRegistrationIdentityOrFileEvidenceIsRejected() {
        var changed = Map.<String, Object>ofEntries(
                Map.entry("jobId", UUID.randomUUID().toString()), Map.entry("model", source),
                Map.entry("sourceModel", "gemma4:26b"), Map.entry("baseRevision", "f".repeat(40)),
                Map.entry("baseModel", "other/base"), Map.entry("artifactSha256", "invalid"),
                Map.entry("manifestSha256", "invalid"), Map.entry("artifactSize", 12.5),
                Map.entry("quantization", "F16"), Map.entry("version", "other"));
        for (var entry : changed.entrySet()) {
            var data = new LinkedHashMap<>(ready()); data.put(entry.getKey(), entry.getValue());
            assertThatThrownBy(() -> service.registration(origin(), data)).isInstanceOf(IllegalStateException.class);
        }
        var foreign = new LinkedHashMap<>(origin()); foreign.put("targetKey", "other-server");
        assertThatThrownBy(() -> service.registration(foreign, ready())).isInstanceOf(IllegalStateException.class);
        var unfinished = new LinkedHashMap<>(origin()); unfinished.put("status", "UNKNOWN");
        assertThatThrownBy(() -> service.registration(unfinished, ready())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository);
    }

    @Test void appliedBaseModelCreatesDigestVersionAndPreservesConfirmationTime() {
        service.deployment(operation(source, false));
        var version = ArgumentCaptor.forClass(Version.class);
        var deployment = ArgumentCaptor.forClass(Deployment.class);
        verify(repository).deployment(isNull(), version.capture(), deployment.capture());
        assertThat(version.getValue().artifactType()).isEqualTo("BASE");
        assertThat(version.getValue().version()).isEqualTo("installed-" + digest);
        assertThat(version.getValue().metadata()).containsEntry("historySource", "RUNTIME_SELECTION");
        assertThat(deployment.getValue()).satisfies(value -> {
            assertThat(value.operationId()).isEqualTo(operationId);
            assertThat(value.recordedAt()).isEqualTo(confirmedAt);
            assertThat(value.revision()).isEqualTo(2L);
            assertThat(value.metadata()).containsEntry("deploymentTimeSource", "OPERATION_CONFIRMATION");
        });
        verifyNoInteractions(operations);
        verify(client, never()).inference(anyString(), anyString(), anyMap(), anyBoolean());
    }

    @Test void trainedDeploymentUsesExistingVerifiedVersionWithoutCallingTrainingOrInference() {
        UUID versionId = UUID.randomUUID();
        when(repository.trainedVersion(model, digest)).thenReturn(Optional.of(versionId));
        service.deployment(operation(model, true));
        verify(repository).deployment(eq(versionId), isNull(), any(Deployment.class));
        verifyNoInteractions(operations);
        verify(client, never()).inference(anyString(), anyString(), anyMap(), anyBoolean());
        verify(client, never()).training(anyString(), anyString(), anyMap());
    }

    @Test void oldTrainedDeploymentRepairsMissingVersionUsingReadOnlyVerifiedImport() {
        UUID versionId = UUID.randomUUID();
        when(repository.trainedVersion(model, digest)).thenReturn(Optional.empty(), Optional.of(versionId));
        when(operations.find(job)).thenReturn(Optional.of(origin()));
        when(client.inference("GET", "/api/models/trained/imports/" + job, Map.of(), false)).thenReturn(ready());
        service.deployment(operation(model, true));
        verify(repository).registration(any(Version.class));
        verify(repository).deployment(eq(versionId), isNull(), any(Deployment.class));
        verify(client).inference("GET", "/api/models/trained/imports/" + job, Map.of(), false);
        verify(client, never()).inference(eq("POST"), anyString(), anyMap(), anyBoolean());
        verify(client, never()).training(anyString(), anyString(), anyMap());
    }

    @Test void listHistoryWithoutRegisteredVersionRequiresRepairWithoutAnyRemoteLookup() {
        when(repository.trainedVersion(model, digest)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deploymentFromList(operation(model, true))).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).registration(any());
        verify(repository, never()).deployment(any(), any(), any());
        verifyNoInteractions(operations);
        verify(client, never()).inference(anyString(), anyString(), anyMap(), anyBoolean());
        verify(client, never()).training(anyString(), anyString(), anyMap());
    }

    @Test void listHistoryRecordsExistingTrainedAndBaseVersionsWithoutRemoteLookup() {
        UUID versionId = UUID.randomUUID();
        when(repository.trainedVersion(model, digest)).thenReturn(Optional.of(versionId));
        service.deploymentFromList(operation(model, true));
        service.deploymentFromList(operation(source, false));
        verify(repository).deployment(eq(versionId), isNull(), any(Deployment.class));
        verify(repository).deployment(isNull(), argThat(version -> "BASE".equals(version.artifactType())), any(Deployment.class));
        verifyNoInteractions(operations);
        verify(client, never()).inference(anyString(), anyString(), anyMap(), anyBoolean());
        verify(client, never()).training(anyString(), anyString(), anyMap());
    }

    @Test void rollbackIsRecordedAsConfirmedDeploymentAndMissingTimeIsExplicitlyObserved() {
        var row = operation(source, false); row.put("action", "MODEL_ROLLBACK"); row.put("status", "ROLLED_BACK"); row.remove("updatedAt");
        var result = new LinkedHashMap<>((Map<String, Object>) row.get("result")); result.put("status", "ROLLED_BACK"); row.put("result", result);
        service.deployment(row);
        var deployment = ArgumentCaptor.forClass(Deployment.class);
        verify(repository).deployment(isNull(), any(Version.class), deployment.capture());
        assertThat(deployment.getValue().metadata()).containsEntry("action", "MODEL_ROLLBACK")
                .containsEntry("deploymentTimeSource", "OBSERVED");
        assertThat(deployment.getValue().recordedAt()).isNotNull();
    }

    @Test void missingDigestRevisionForeignTargetOrUnsupportedAdapterCannotInventDeployment() {
        for (var change : List.of(Map.entry("digest", (Object) "invalid"), Map.entry("revision", (Object) 1.5),
                Map.entry("revision", (Object) (-1)), Map.entry("provider", (Object) "mlx"))) {
            var row = operation(source, false);
            var result = new LinkedHashMap<>((Map<String, Object>) row.get("result")); result.put(change.getKey(), change.getValue()); row.put("result", result);
            assertThatThrownBy(() -> service.deployment(row)).isInstanceOf(IllegalStateException.class);
        }
        var foreign = operation(source, false); foreign.put("targetKey", "foreign");
        assertThatThrownBy(() -> service.deployment(foreign)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository);
    }

    private Map<String, Object> origin() {
        return Map.of("id", job.toString(), "actorId", actor.toString(), "action", "TRAINING_START", "status", "SUCCEEDED", "targetKey", "training-target",
                "result", Map.of("target", Map.of("sourceModel", source, "baseModel", base, "revision", "b".repeat(40))));
    }

    private Map<String, Object> ready() {
        var value = new LinkedHashMap<String, Object>();
        value.put("jobId", job.toString()); value.put("model", model); value.put("version", "trained-web-" + job);
        value.put("status", "READY"); value.put("sourceModel", source); value.put("baseModel", base); value.put("baseRevision", "b".repeat(40));
        value.put("digest", digest); value.put("artifactSha256", artifact); value.put("manifestSha256", "c".repeat(64));
        value.put("artifactSize", 16_547_400_128L); value.put("quantization", "Q4_K_M");
        return value;
    }

    private LinkedHashMap<String, Object> operation(String selected, boolean trained) {
        var result = new LinkedHashMap<String, Object>();
        result.put("status", "APPLIED"); result.put("model", selected); result.put("digest", digest); result.put("revision", 2);
        if (trained) { result.put("trainedModel", true); result.put("trainingJobId", job.toString()); }
        return new LinkedHashMap<>(Map.of("id", operationId.toString(), "actorId", actor.toString(), "action", "MODEL_ACTIVATE", "status", "APPLIED",
                "targetKey", "inference-target", "updatedAt", confirmedAt.toString(), "result", result));
    }
}
