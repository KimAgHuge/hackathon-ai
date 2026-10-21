package com.doenaai.model.service;

import com.doenaai.model.dto.ModelEvaluationRequest;
import com.doenaai.model.dto.ModelVersionCreateRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class ModelInputPolicyTest {
    @Test void acceptsOnlyRelativeNasMetadataAndValidChecksum() {
        var value = ModelInputPolicy.version(version("models/모델 v1/weights.safetensors", "등록 메모"));
        assertThat(value.artifactUri()).isEqualTo("models/모델 v1/weights.safetensors");
        assertThat(value.checksum()).hasSize(64);
    }

    @Test void rejectsAbsoluteTraversalNetworkEncodedAndControlPaths() {
        for (String path : List.of("/mnt/models/a", "../a", "models/../a", "models/ ./a", "a//b", "a/",
                "https://example.invalid/a", "smb://nas/a", "C:\\models\\a", "\\\\nas\\a", "a/%2e%2e/b", "a\nb")) {
            assertThatThrownBy(() -> ModelInputPolicy.version(version(path, "등록 메모")))
                    .as(path).isInstanceOf(ModelCatalogException.class);
        }
    }

    @Test void rejectsSensitiveNotesWithoutIncludingTheOriginal() {
        for (String value : List.of("password=private-value", "token: private-value", "Bearer private-value",
                "비밀번호=private-value", "-----BEGIN PRIVATE KEY-----", "https://user:private-value@example.invalid/model",
                "ghp_" + "a".repeat(30), "hf_" + "a".repeat(30), "person@example.invalid")) {
            assertThatThrownBy(() -> ModelInputPolicy.version(version("models/v1", value)))
                    .isInstanceOf(ModelCatalogException.class).hasMessageNotContaining("private-value");
        }
    }

    @Test void rejectsCredentialsInBaseReferencesAndEvaluationNotes() {
        for (String value : List.of("https://user:private-value@example.invalid/model", "hf_" + "a".repeat(30), "person@example.invalid")) {
            var request = version("models/v1", null);
            assertThatThrownBy(() -> ModelInputPolicy.version(new ModelVersionCreateRequest(null, request.modelName(), null,
                    null, value, "v1", "LORA", null, null, null, null, null)))
                    .isInstanceOf(ModelCatalogException.class);
            assertThatThrownBy(() -> ModelInputPolicy.evaluation(new ModelEvaluationRequest("PASSED", null, value, Map.of())))
                    .isInstanceOf(ModelCatalogException.class);
        }
    }

    @Test void requiresNewModelNameButAllowsExistingModelId() {
        var base = version("models/v1", null);
        assertThatThrownBy(() -> ModelInputPolicy.version(new ModelVersionCreateRequest(null, " ", null,
                null, base.baseModelRef(), "v1", "LORA", null, null, null, null, null)))
                .isInstanceOf(ModelCatalogException.class);
        assertThat(ModelInputPolicy.version(new ModelVersionCreateRequest(UUID.randomUUID(), null, null,
                null, base.baseModelRef(), "v1", "LORA", null, null, null, null, null)).modelName()).isNull();
    }

    @Test void validatesManualEvaluationMetricsAndStatus() {
        var value = ModelInputPolicy.evaluation(new ModelEvaluationRequest("PASSED", BigDecimal.valueOf(100), "수동 확인",
                Map.of("sampleCount", 2, "jsonCompliance", 100, "databaseJudgment", 0,
                        "koreanQuality", 90.5, "averageResponseSeconds", 0)));
        assertThat(value.metrics()).hasSize(5);
        for (Map<String, Object> metrics : List.<Map<String, Object>>of(Map.of("unknown", 1), Map.of("sampleCount", 0),
                Map.of("sampleCount", 1.5), Map.of("jsonCompliance", 101), Map.of("koreanQuality", -1),
                Map.of("averageResponseSeconds", -0.1), Map.of("jsonCompliance", "90"))) {
            assertThatThrownBy(() -> ModelInputPolicy.evaluation(new ModelEvaluationRequest("PASSED", null, "수동 확인", metrics)))
                    .isInstanceOf(ModelCatalogException.class);
        }
        assertThatThrownBy(() -> ModelInputPolicy.evaluation(new ModelEvaluationRequest("RUNNING", null, "메모", Map.of())))
                .isInstanceOf(ModelCatalogException.class);
        assertThatThrownBy(() -> ModelInputPolicy.evaluation(new ModelEvaluationRequest("PASSED", new BigDecimal("100.01"), "메모", Map.of())))
                .isInstanceOf(ModelCatalogException.class);
        assertThatThrownBy(() -> ModelInputPolicy.evaluation(new ModelEvaluationRequest("FAILED", null, " ", Map.of())))
                .isInstanceOf(ModelCatalogException.class);
    }

    private static ModelVersionCreateRequest version(String path, String notes) {
        return new ModelVersionCreateRequest(null, "분석 모델", "모델 설명", "MLX", "example/model", "v1", "LORA",
                path, "adapters/v1", "a".repeat(64), "검토 데이터", notes);
    }
}
