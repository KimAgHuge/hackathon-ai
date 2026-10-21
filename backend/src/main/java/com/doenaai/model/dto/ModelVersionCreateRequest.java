package com.doenaai.model.dto;

import java.util.UUID;

/** 모델 파일을 열거나 실행하지 않고 보관할 등록 정보다. */
public record ModelVersionCreateRequest(UUID modelId, String modelName, String modelDescription,
        String provider, String baseModelRef, String version, String artifactType,
        String artifactUri, String adapterUri, String checksum, String datasetName, String notes) {}
