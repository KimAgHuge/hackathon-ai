package com.doenaai.model.service;

import com.doenaai.model.dto.ModelEvaluationRequest;
import com.doenaai.model.dto.ModelVersionCreateRequest;
import com.doenaai.model.repository.ModelCatalogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModelCatalogServiceTest {
    private final ModelCatalogRepository repository = mock(ModelCatalogRepository.class);
    private final ModelCatalogService service = new ModelCatalogService(repository);
    private final UUID userId = UUID.randomUUID();

    @Test void queriesOneExtraRowForEveryListAndMarksOnlyActuallyTruncatedLists() {
        when(repository.models(101)).thenReturn(rows(101));
        when(repository.versions(101)).thenReturn(rows(100));
        when(repository.trainingJobs(101)).thenReturn(rows(0));
        when(repository.deployments(101)).thenReturn(rows(101));
        var response = service.catalog();
        assertThat((List<?>) response.get("models")).hasSize(100);
        assertThat((List<?>) response.get("versions")).hasSize(100);
        assertThat((Map<?, ?>) response.get("truncated")).isEqualTo(Map.of("models", true,
                "versions", false, "trainingJobs", false, "deployments", true));
        assertThat(response.get("capabilities").toString()).contains("trainingExecution=false", "deploymentExecution=false", "rollbackExecution=false");
    }

    @Test void manualPassedAndValidatedNeverPermitDeploymentOrRollback() {
        UUID id = UUID.randomUUID();
        when(repository.version(id)).thenReturn(Optional.of(Map.of("id", id, "status", "VALIDATED", "latestEvaluationStatus", "PASSED")));
        when(repository.evaluations(id)).thenReturn(List.of(Map.of("status", "PASSED", "evaluationType", "MANUAL")));
        when(repository.versionDeployments(id)).thenReturn(List.of(Map.of("status", "ACTIVE")));
        var value = service.version(id);
        var readiness = (Map<?, ?>) value.get("readiness");
        assertThat(readiness.get("canDeploy")).isEqualTo(false);
        assertThat(readiness.get("canRollback")).isEqualTo(false);
        assertThat((List<?>) readiness.get("checks")).hasSize(6);
    }

    @Test void detailHistoriesReturnOnlyOneHundredRowsAndExposeActualOverflow() {
        UUID id = UUID.randomUUID();
        when(repository.version(id)).thenReturn(Optional.of(Map.of("id", id)));
        when(repository.evaluations(id)).thenReturn(rows(101));
        when(repository.versionDeployments(id)).thenReturn(rows(100));
        var value = service.version(id);
        assertThat((List<?>) value.get("evaluations")).hasSize(100);
        assertThat((List<?>) value.get("deployments")).hasSize(100);
        assertThat(value.get("detailTruncated")).isEqualTo(Map.of("evaluations", true, "deployments", false));
    }

    @Test void automaticallyRegisteredVersionShowsVerifiedFilesWithoutClaimingQualityApproval() {
        UUID id = UUID.randomUUID();
        when(repository.version(id)).thenReturn(Optional.of(Map.of("id", id, "registrationVerified", true)));
        var result = service.version(id);
        var checks = (List<Map<String, Object>>) ((Map<?, ?>) result.get("readiness")).get("checks");
        assertThat(checks.stream().filter(check -> "PASSED".equals(check.get("status"))).count()).isEqualTo(3);
        assertThat(checks.stream().filter(check -> "automaticEvaluation".equals(check.get("key"))).findFirst().orElseThrow())
                .containsEntry("status", "UNAVAILABLE");
    }

    @Test void existingModelRegistrationNeverChangesLogicalModelInformation() {
        UUID modelId = UUID.randomUUID();
        when(repository.modelExists(modelId)).thenReturn(true);
        when(repository.version(any())).thenAnswer(call -> Optional.of(Map.<String, Object>of("id", call.getArgument(0), "status", "READY")));
        var response = service.createVersion(request(modelId), userId);
        verify(repository, never()).createModel(any(), any(), any());
        verify(repository).createVersion(any(), eq(modelId), any(), eq(userId));
        assertThat(response.get("message").toString()).contains("등록 상태", "파일");
    }

    @Test void missingModelReturns404BeforeAnyWrite() {
        assertThatThrownBy(() -> service.createVersion(request(UUID.randomUUID()), userId))
                .isInstanceOfSatisfying(ModelCatalogException.class, error -> assertThat(error.status().value()).isEqualTo(404));
        verify(repository, never()).createVersion(any(), any(), any(), any());
        verify(repository, never()).createModel(any(), any(), any());
    }

    @Test void secondInsertFailureRollsBackNewModelAndReturnsSafeConflict() {
        var transactions = new RecordingTransactions();
        var proxy = new ProxyFactory(service);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        doThrow(new DuplicateKeyException("SQL password=private-value"))
                .when(repository).createVersion(any(), any(), any(), any());
        assertThatThrownBy(() -> ((ModelCatalogService) proxy.getProxy()).createVersion(request(null), userId))
                .isInstanceOfSatisfying(ModelCatalogException.class, error -> assertThat(error.status().value()).isEqualTo(409))
                .hasMessageNotContaining("SQL").hasMessageNotContaining("private-value");
        verify(repository).createModel(any(), any(), eq(userId));
        assertThat(transactions.rolledBack).isTrue();
        assertThat(transactions.committed).isFalse();
    }

    @Test void manualEvaluationWritesActorAndLeavesVersionUnchanged() {
        UUID id = UUID.randomUUID();
        when(repository.version(id)).thenReturn(Optional.of(Map.of("id", id, "status", "READY")));
        var request = new ModelEvaluationRequest("PASSED", BigDecimal.valueOf(95), "검토자가 직접 확인했습니다.", Map.of("sampleCount", 10));
        var response = service.recordEvaluation(id, request, userId);
        verify(repository).createEvaluation(any(), eq(id), any(), eq(userId));
        verify(repository, never()).createVersion(any(), any(), any(), any());
        assertThat(response.get("status")).isEqualTo("READY");
        assertThat(response.get("message").toString()).contains("수동 평가", "배포하지 않았습니다");
    }

    private static List<Map<String, Object>> rows(int count) {
        return IntStream.range(0, count).mapToObj(index -> Map.<String, Object>of("id", index)).toList();
    }

    private static ModelVersionCreateRequest request(UUID modelId) {
        return new ModelVersionCreateRequest(modelId, "분석 모델", null, "MLX", "example/model", "v1", "LORA",
                "models/v1", "adapters/v1", null, null, "등록 기록");
    }

    private static class RecordingTransactions implements PlatformTransactionManager {
        boolean committed;
        boolean rolledBack;
        public TransactionStatus getTransaction(TransactionDefinition definition) { return new SimpleTransactionStatus(); }
        public void commit(TransactionStatus status) { committed = true; }
        public void rollback(TransactionStatus status) { rolledBack = true; }
    }
}
