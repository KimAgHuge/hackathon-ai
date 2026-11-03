package com.doenaai.analysis.dto;

import com.doenaai.requirement.entity.RequirementVersion;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 분석 작업에 고정된 요구사항 버전의 표시 정보다. 현재 버전이나 AI 응답으로 바꾸지 않는다. */
public record AnalysisRequirementContext(
        UUID requirementId,
        UUID versionId,
        int versionNumber,
        String title,
        String originalContent,
        String refinedContent,
        List<Object> acceptanceCriteria,
        String latestFollowUpQuestion,
        UUID sourceAnalysisJobId
) {
    public static AnalysisRequirementContext from(RequirementVersion version) {
        String latestQuestion = null;
        if (version.sourceAnalysisJobId() != null && version.clarifications() != null) {
            for (int index = version.clarifications().size() - 1; index >= 0; index--) {
                Object value = version.clarifications().get(index);
                if (value instanceof Map<?, ?> item && "USER_QUESTION".equals(item.get("kind"))
                        && version.sourceAnalysisJobId().toString().equals(item.get("sourceAnalysisJobId"))
                        && item.get("question") instanceof String question && !question.isBlank()) {
                    latestQuestion = question;
                    break;
                }
            }
        }
        return new AnalysisRequirementContext(version.requirementId(), version.id(), version.versionNumber(),
                version.title(), version.originalContent(), version.refinedContent(),
                version.acceptanceCriteria() == null ? List.of() : version.acceptanceCriteria(),
                latestQuestion, version.sourceAnalysisJobId());
    }
}
