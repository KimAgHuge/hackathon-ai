package com.doenaai.analysis.controller;

import com.doenaai.analysis.dto.AnalysisResultResponse;
import com.doenaai.analysis.dto.AnalysisReviewRequest;
import com.doenaai.analysis.dto.TrainingCaseDraftResponse;
import com.doenaai.analysis.dto.TrainingTransferRequest;
import com.doenaai.analysis.service.AnalysisResultService;
import com.doenaai.analysis.service.TrainingTransferClient;
import com.doenaai.analysis.service.TrainingEvidenceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
public class AnalysisResultController {
    private final AnalysisResultService results;
    private final TrainingTransferClient trainingTransfers;
    private final TrainingEvidenceService trainingEvidence;

    /** 인증된 사용자가 접근 가능한 완료 작업의 AI 결과와 입력 근거를 반환한다. */
    @GetMapping("/api/analysis-jobs/{jobId}/result")
    @PreAuthorize("@permissionChecker.has(authentication, 'ANALYSIS_READ')")
    public AnalysisResultResponse get(@PathVariable UUID jobId, @AuthenticationPrincipal Jwt jwt) {
        return results.get(jobId, UUID.fromString(jwt.getSubject()));
    }

    /** 완료된 분석을 프로젝트 관리 권한과 분석 승인 권한이 있는 사람이 검토한다. */
    @PutMapping("/api/analysis-jobs/{jobId}/review")
    @PreAuthorize("@permissionChecker.has(authentication, 'ANALYSIS_APPROVE')")
    public AnalysisResultResponse review(@PathVariable UUID jobId,
                                         @Valid @RequestBody AnalysisReviewRequest request,
                                         @AuthenticationPrincipal Jwt jwt) {
        return results.review(jobId, UUID.fromString(jwt.getSubject()), request);
    }

    /** 승인된 결과의 비식별 전 학습 사례 초안을 권한 있는 프로젝트 관리자에게만 반환한다. */
    @GetMapping("/api/analysis-jobs/{jobId}/training-case-draft")
    @PreAuthorize("@permissionChecker.has(authentication, 'ANALYSIS_APPROVE')")
    public ResponseEntity<TrainingCaseDraftResponse> trainingDraft(@PathVariable UUID jobId,
                                                                    @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(results.trainingDraft(jobId, UUID.fromString(jwt.getSubject())));
    }

    /** 승인한 분석과 연결된 검토 사례만 AI 학습 서버로 보낸다. */
    @PostMapping("/api/analysis-jobs/{jobId}/training-transfer")
    @PreAuthorize("@permissionChecker.has(authentication, 'ANALYSIS_APPROVE')")
    public ResponseEntity<Map<String, Object>> publishTrainingCase(@PathVariable UUID jobId,
            @Valid @RequestBody TrainingTransferRequest request, @AuthenticationPrincipal Jwt jwt) {
        var draft = results.trainingDraft(jobId, UUID.fromString(jwt.getSubject()));
        var value = new LinkedHashMap<>(request.caseDraft());
        var expectedProjectId = ((Map<?, ?>) draft.caseDraft().get("request")).get("projectId");
        var expectedVersionId = ((Map<?, ?>) draft.caseDraft().get("snapshot")).get("requirementVersionId");
        if (!request.reviewConfirmed() || !Boolean.TRUE.equals(value.get("humanApproved"))
                || !Boolean.TRUE.equals(value.get("sanitized"))
                || !draft.caseDraft().get("caseId").equals(value.get("caseId"))
                || !(value.get("request") instanceof Map<?, ?> input)
                || !expectedProjectId.equals(input.get("projectId"))
                || !(value.get("snapshot") instanceof Map<?, ?> snapshot)
                || !expectedVersionId.equals(snapshot.get("requirementVersionId"))
                || !(value.get("approval") instanceof Map<?, ?> approval)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "학습 사례의 검토 확인 또는 분석 연결 정보가 올바르지 않습니다.");
        }
        // 사용자가 편집한 식별자 대신 저장된 프로젝트와 현재 로그인 사용자에서 만든 값을 사용한다.
        value.put("projectGroup", draft.caseDraft().get("projectGroup"));
        var expectedApproval = (Map<?, ?>) draft.caseDraft().get("approval");
        var confirmedApproval = new LinkedHashMap<String, Object>();
        approval.forEach((key, fieldValue) -> confirmedApproval.put((String) key, fieldValue));
        confirmedApproval.put("reviewerId", expectedApproval.get("reviewerId"));
        if (expectedApproval.get("approvedAt") != null) {
            confirmedApproval.put("approvedAt", expectedApproval.get("approvedAt"));
        }
        value.put("approval", confirmedApproval);
        var verified = trainingEvidence.verifiedCase(jobId, UUID.fromString(jwt.getSubject()), value);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(trainingTransfers.publish(jobId, verified));
    }

    /** 화면을 다시 열어도 같은 승인 분석의 수신 상태를 확인한다. */
    @GetMapping("/api/analysis-jobs/{jobId}/training-transfer")
    @PreAuthorize("@permissionChecker.has(authentication, 'ANALYSIS_APPROVE')")
    public ResponseEntity<Map<String, Object>> trainingTransferStatus(@PathVariable UUID jobId,
            @AuthenticationPrincipal Jwt jwt) {
        results.trainingDraft(jobId, UUID.fromString(jwt.getSubject()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(trainingTransfers.status(jobId));
    }
}
