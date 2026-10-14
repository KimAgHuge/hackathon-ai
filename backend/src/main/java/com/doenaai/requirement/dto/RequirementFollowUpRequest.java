package com.doenaai.requirement.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** 저장된 분석 질문에 답하거나 추가 질문을 남겨 요구사항의 다음 버전을 만든다. */
public record RequirementFollowUpRequest(
        @NotNull UUID sourceAnalysisJobId,
        @NotNull UUID expectedVersionId,
        @Size(max = 20) List<@NotNull @Valid Answer> answers,
        @Size(max = 2000) String followUpQuestion
) {
    public record Answer(
            @NotNull @Min(0) Integer questionIndex,
            @NotBlank @Size(max = 4000) String answer
    ) {}
}
