package com.doenaai.model.dto;

import java.util.UUID;

/** 실행 경로나 명령어는 받지 않고 선택 모델과 중복 방지 식별자만 받는다. */
public record ModelExecutionRequest(UUID operationId, String model, String digest, String probeToken,
                                    Long expectedRevision, Integer iterations) { }
