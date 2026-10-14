package com.doenaai.requirement.service;

import com.doenaai.analysis.repository.AnalysisJobRepository;
import com.doenaai.analysis.repository.AnalysisResultRepository;
import com.doenaai.project.service.ProjectService;
import com.doenaai.requirement.dto.RequirementFollowUpRequest;
import com.doenaai.requirement.dto.RequirementVersionCreateRequest;
import com.doenaai.requirement.dto.RequirementVersionResponse;
import com.doenaai.requirement.repository.RequirementRepository;
import com.doenaai.requirement.repository.RequirementVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RequirementFollowUpService {
    private static final Set<String> EDITORS = Set.of("ADMIN", "OWNER", "MANAGER", "PM", "PL");

    private final RequirementRepository requirements;
    private final RequirementVersionRepository versions;
    private final ProjectService projects;
    private final AnalysisJobRepository jobs;
    private final AnalysisResultRepository results;

    /** 분석 질문 ID는 모델 문구가 아닌 (분석 job ID, 질문 순번)으로 고정하고 새 요구사항 버전을 저장한다. */
    @Transactional
    public RequirementVersionResponse create(UUID requirementId, RequirementFollowUpRequest request, UUID userId) {
        var requirement = requirements.findById(requirementId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "요구사항을 찾을 수 없습니다."));
        var access = projects.getAccessContext(requirement.getProjectId(), userId);
        if (!EDITORS.contains(access.memberRole())) {
            throw new AccessDeniedException("요구사항 추가 질문 권한이 없습니다.");
        }
        if (!requirement.getOrganizationId().equals(access.organizationId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "요구사항을 찾을 수 없습니다.");
        }

        versions.lockRequirement(requirementId);
        var current = versions.findCurrent(requirementId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "현재 요구사항 버전을 찾을 수 없습니다."));
        if (!current.id().equals(request.expectedVersionId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "요구사항 버전이 변경되었습니다. 새로고침 후 다시 작성해 주세요.");
        }
        var job = jobs.findById(request.sourceAnalysisJobId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "이전 분석을 찾을 수 없습니다."));
        if (!"COMPLETED".equals(job.status()) || !requirement.getProjectId().equals(job.projectId())
                || !current.id().equals(job.requirementVersionId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "현재 요구사항 버전의 완료된 분석만 이어서 질문할 수 있습니다.");
        }
        var result = results.findByJobId(job.jobId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "이전 분석 결과를 찾을 수 없습니다."));
        List<?> questions = openQuestions(result.result());
        List<RequirementFollowUpRequest.Answer> answers = request.answers() == null ? List.of() : request.answers();
        String followUpQuestion = request.followUpQuestion() == null ? "" : request.followUpQuestion().trim();
        if (answers.isEmpty() && followUpQuestion.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "답변 또는 추가 질문을 입력해 주세요.");
        }
        int inputLength = followUpQuestion.length() + answers.stream().mapToInt(answer -> answer.answer().trim().length()).sum();
        if (inputLength > 8_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "답변과 추가 질문은 합계 8,000자 이내로 작성해 주세요.");
        }

        List<Object> clarifications = new ArrayList<>(current.clarifications());
        Set<Integer> seen = new HashSet<>();
        String createdAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        for (var answer : answers) {
            int index = answer.questionIndex();
            if (index < 0 || !seen.add(index) || index >= questions.size() || !(questions.get(index) instanceof String question)
                    || question.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "확인 질문 번호가 올바르지 않습니다.");
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", UUID.randomUUID().toString());
            entry.put("kind", "AI_QUESTION_ANSWER");
            entry.put("sourceAnalysisJobId", job.jobId().toString());
            entry.put("questionIndex", index);
            entry.put("questionKey", job.jobId() + ":" + index);
            entry.put("question", question);
            entry.put("answer", answer.answer().trim());
            entry.put("createdBy", userId.toString());
            entry.put("createdAt", createdAt);
            clarifications.add(entry);
        }
        if (!followUpQuestion.isEmpty()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", UUID.randomUUID().toString());
            entry.put("kind", "USER_QUESTION");
            entry.put("sourceAnalysisJobId", job.jobId().toString());
            entry.put("question", followUpQuestion);
            entry.put("createdBy", userId.toString());
            entry.put("createdAt", createdAt);
            clarifications.add(entry);
        }
        var snapshot = new RequirementVersionCreateRequest(current.title(), current.originalContent(),
                current.refinedContent(), current.acceptanceCriteria(), current.attachmentsMetadata(),
                "이전 분석의 확인 질문 답변 및 추가 질문");
        int nextVersion = versions.findNextVersionNumber(requirementId);
        versions.markCurrentVersionHistorical(requirementId);
        var saved = versions.insert(requirementId, nextVersion, snapshot, userId, clarifications, job.jobId(), current.id());
        return RequirementVersionResponse.from(saved, false);
    }

    private List<?> openQuestions(Map<String, Object> result) {
        Object impact = result.get("databaseImpact");
        if (impact instanceof Map<?, ?> map && map.get("openQuestions") instanceof List<?> questions) {
            return questions;
        }
        return List.of();
    }
}
