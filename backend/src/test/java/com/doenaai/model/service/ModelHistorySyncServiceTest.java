package com.doenaai.model.service;

import com.doenaai.model.repository.ModelOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 원격 작업을 다시 실행하지 않고 완료된 기록만 보완하는지 확인한다. */
class ModelHistorySyncServiceTest {
    private final ModelOperationRepository repository = mock(ModelOperationRepository.class);
    private final ModelExecutionClient client = mock(ModelExecutionClient.class);
    private final TrainedModelManagementService trained = mock(TrainedModelManagementService.class);
    private final ModelExecutionService execution = mock(ModelExecutionService.class);
    private final ModelHistorySyncService service = new ModelHistorySyncService(repository, client, trained, execution);

    @BeforeEach void setup() {
        when(client.targetKey(true)).thenReturn("training-target");
        when(client.targetKey(false)).thenReturn("inference-target");
        when(repository.historyTraining("training-target")).thenReturn(List.of());
        when(repository.historyTransitions("inference-target")).thenReturn(List.of());
    }

    @Test void synchronizesReadyRegistrationAndCompletedTransitionsWithoutRepeatingRemoteMutations() {
        UUID training = UUID.randomUUID(), activation = UUID.randomUUID(), rollback = UUID.randomUUID();
        when(repository.historyTraining("training-target")).thenReturn(List.of(row(training)));
        when(repository.historyTransitions("inference-target")).thenReturn(List.of(row(activation), row(rollback)));
        when(trained.importStatus(training)).thenReturn(Map.of("status", "READY"));
        when(execution.operation(activation)).thenReturn(Map.of("status", "APPLIED"));
        when(execution.operation(rollback)).thenReturn(Map.of("status", "ROLLED_BACK"));

        assertThat(service.synchronize()).containsEntry("status", "SUCCEEDED")
                .containsEntry("registrationsChecked", 1).containsEntry("deploymentsChecked", 2).containsEntry("failures", 0);

        verify(trained).importStatus(training);
        verify(execution).operation(activation);
        verify(execution).operation(rollback);
        verify(client).targetKey(true);
        verify(client).targetKey(false);
        verifyNoMoreInteractions(trained, execution, client);
    }

    @Test void emptyHistoryCompletesWithoutContactingEitherRemoteServer() {
        assertThat(service.synchronize()).containsEntry("status", "SUCCEEDED")
                .containsEntry("registrationsChecked", 0).containsEntry("deploymentsChecked", 0).containsEntry("failures", 0);
        verifyNoInteractions(trained, execution);
        verify(client, never()).training(anyString(), anyString(), anyMap());
        verify(client, never()).inference(anyString(), anyString(), anyMap(), anyBoolean());
    }

    @Test void unknownRegistrationAndFailedTransitionReturnPartialInsteadOfClaimingCompletion() {
        UUID training = UUID.randomUUID(), activation = UUID.randomUUID();
        when(repository.historyTraining("training-target")).thenReturn(List.of(row(training)));
        when(repository.historyTransitions("inference-target")).thenReturn(List.of(row(activation)));
        when(trained.importStatus(training)).thenReturn(Map.of("status", "UNKNOWN"));
        when(execution.operation(activation)).thenReturn(Map.of("status", "FAILED"));

        assertThat(service.synchronize()).containsEntry("status", "PARTIAL")
                .containsEntry("registrationsChecked", 0).containsEntry("deploymentsChecked", 0).containsEntry("failures", 2);
    }

    @Test void persistenceOrConnectionFailuresDoNotPreventOtherRecordsFromBeingChecked() {
        UUID broken = UUID.randomUUID(), ready = UUID.randomUUID(), activation = UUID.randomUUID();
        when(repository.historyTraining("training-target")).thenReturn(List.of(row(broken), row(ready)));
        when(repository.historyTransitions("inference-target")).thenReturn(List.of(row(activation)));
        when(trained.importStatus(broken)).thenThrow(new IllegalStateException("연결 실패"));
        when(trained.importStatus(ready)).thenReturn(Map.of("status", "READY"));
        when(execution.operation(activation)).thenReturn(Map.of("status", "APPLIED", "historySyncRequired", true));

        assertThat(service.synchronize()).containsEntry("status", "PARTIAL")
                .containsEntry("registrationsChecked", 1).containsEntry("deploymentsChecked", 1).containsEntry("failures", 2);
        verify(trained).importStatus(ready);
        verify(execution).operation(activation);
    }

    @Test void readyRegistrationWithUnsavedHistoryRemainsPartial() {
        UUID training = UUID.randomUUID();
        when(repository.historyTraining("training-target")).thenReturn(List.of(row(training)));
        when(trained.importStatus(training)).thenReturn(Map.of("status", "READY", "historySyncRequired", true));
        assertThat(service.synchronize()).containsEntry("status", "PARTIAL")
                .containsEntry("registrationsChecked", 1).containsEntry("failures", 1);
    }

    @Test void capsChecksAtOneHundredAcrossRegistrationAndDeploymentHistory() {
        var training = IntStream.range(0, 60).mapToObj(index -> row(UUID.randomUUID())).toList();
        var transitions = IntStream.range(0, 60).mapToObj(index -> row(UUID.randomUUID())).toList();
        when(repository.historyTraining("training-target")).thenReturn(training);
        when(repository.historyTransitions("inference-target")).thenReturn(transitions);
        when(trained.importStatus(any())).thenReturn(Map.of("status", "READY"));
        when(execution.operation(any())).thenReturn(Map.of("status", "APPLIED"));

        assertThat(service.synchronize()).containsEntry("status", "PARTIAL")
                .containsEntry("registrationsChecked", 60).containsEntry("deploymentsChecked", 40).containsEntry("failures", 1);
        verify(trained, times(60)).importStatus(any());
        verify(execution, times(40)).operation(any());
        verify(execution, never()).operation(UUID.fromString((String) transitions.get(40).get("id")));
    }

    @Test void unavailableRegistrationCannotBeReportedAsCompletelySynchronized() {
        UUID id = UUID.randomUUID();
        when(repository.historyTraining("training-target")).thenReturn(List.of(row(id)));
        for (String status : List.of("UNAVAILABLE", "FAILED")) {
            when(trained.importStatus(id)).thenReturn(Map.of("status", status));
            assertThat(service.synchronize()).containsEntry("status", "PARTIAL").containsEntry("failures", 1);
        }
    }

    private static Map<String, Object> row(UUID id) { return Map.of("id", id.toString()); }
}
