package com.doenaai.analysis.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

/** 게시 동의와 분리해 근거 보강·사전 검사에 필요한 학습 사례만 받는다. */
public record TrainingCaseRequest(@NotNull Map<String, Object> caseDraft) {}
