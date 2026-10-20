package com.doenaai.analysis.service;

import com.doenaai.analysis.dto.AnalysisResultResponse;
import com.doenaai.analysis.dto.AnalysisReviewDecision;
import com.doenaai.analysis.dto.AnalysisReviewRequest;
import com.doenaai.analysis.dto.TrainingCaseDraftResponse;
import com.doenaai.analysis.repository.AnalysisResultRepository;
import com.doenaai.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AnalysisResultService {
    private final AnalysisJobService jobs;
    private final AnalysisResultRepository results;
    private final ProjectService projects;
    private final AnalysisReviewValidator validator;

    /** 완료된 jobId의 저장 결과를 반환한다; userId에 해당 프로젝트 조회 권한이 필요하다. */
    public AnalysisResultResponse get(UUID jobId, UUID userId) {
        var job = jobs.getAnalysisJob(jobId, userId); // Same project/tenant access check as progress.
        if (!"COMPLETED".equals(job.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "아직 완료되지 않은 분석입니다.");
        }
        return results.findByJobId(jobId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "분석 결과를 찾을 수 없습니다."));
    }

    /** 프로젝트 관리자가 승인한 변경 분석을 비식별 전 학습 사례 초안으로 조회한다. */
    public TrainingCaseDraftResponse trainingDraft(UUID jobId, UUID userId) {
        var job = jobs.getAnalysisJob(jobId, userId);
        projects.assertCanManage(job.projectId(), userId);
        if (!"COMPLETED".equals(job.status()) || job.requirementVersionId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "완료된 변경 요구사항 분석만 학습 초안으로 만들 수 있습니다.");
        }
        var result = results.findByJobId(jobId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "분석 결과를 찾을 수 없습니다."));
        if (!"APPROVED".equals(result.reviewStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "사람이 승인한 분석 결과만 학습 초안으로 만들 수 있습니다.");
        }
        return TrainingCaseDraftBuilder.build(job, result, userId);
    }

    /** 완료된 작업만 한 번 검토하고 AI 원본을 보존한 채 결정과 수정본을 저장한다. */
    @Transactional
    public AnalysisResultResponse review(UUID jobId, UUID userId, AnalysisReviewRequest request) {
        var job = jobs.getAnalysisJob(jobId, userId);
        projects.assertCanManage(job.projectId(), userId);
        if (!"COMPLETED".equals(job.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "완료된 분석만 검토할 수 있습니다.");
        }
        if (request == null || request.decision() == null || request.note() == null ||
                request.note().isBlank() || request.note().length() > 4000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "검토 결정과 이유를 입력해 주세요.");
        }
        if (request.decision() == AnalysisReviewDecision.REJECTED && request.correctedResult() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "반려에는 수정 결과를 함께 저장할 수 없습니다.");
        }
        if (request.correctedResult() != null) validator.validate(request.correctedResult());
        if (request.decision() == AnalysisReviewDecision.APPROVED && request.correctedResult() == null) {
            var original = results.findByJobId(jobId).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "분석 결과를 찾을 수 없습니다."));
            // 구형 결과 조회는 유지하되, 중복 변경안이 있는 원본을 새로 승인하지는 않는다.
            if (original.result().get("databaseImpact") instanceof java.util.Map<?, ?> impact) {
                try {
                    DatabaseChangePolicy.validate(impact);
                } catch (IllegalArgumentException exception) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "AI 원본의 DB 변경안이 검사를 통과하지 못했습니다. 수정본을 검토하거나 새 분석을 시작해 주세요.");
                }
            }
            if (!RiskScorePolicy.matches(original.riskLevel(), original.riskScore(),
                    original.result().get("riskAssessment"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "AI 원본의 위험도 등급과 점수가 일치하지 않습니다. 수정본을 검토하거나 새 분석을 시작해 주세요.");
            }
        }

        if (!results.review(jobId, userId, request.decision(), request.note().trim(), request.correctedResult())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 검토된 분석이거나 결과가 없습니다. 새로고침 후 확인해 주세요.");
        }
        return results.findByJobId(jobId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "분석 결과를 찾을 수 없습니다."));
    }
}
