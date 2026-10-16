package com.doenaai.analysis.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

/** 사람이 정답과 비식별화를 확인한 학습 사례만 전송한다. */
public record TrainingTransferRequest(@NotNull Map<String, Object> caseDraft,
                                      boolean reviewConfirmed) {}
