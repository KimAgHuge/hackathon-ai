package com.doenaai.analysis.controller;

import com.doenaai.analysis.dto.TrainingCaseDraftResponse;
import com.doenaai.analysis.dto.TrainingCaseRequest;
import com.doenaai.analysis.service.TrainingEvidenceService;
import com.doenaai.analysis.service.AnalysisResultService;
import com.doenaai.analysis.service.TrainingTransferClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import java.util.Map;
import java.util.Objects;

@RestController
@RequiredArgsConstructor
public class TrainingEvidenceController {
    private final TrainingEvidenceService evidence;
    private final AnalysisResultService results;
    private final TrainingTransferClient transfers;

    /** 권한과 분석 연결 정보를 확인한 뒤 파일을 게시하지 않고 학습 계약만 검사한다. */
    @PostMapping("/api/analysis-jobs/{jobId}/training-case-validation")
    @PreAuthorize("@permissionChecker.has(authentication, 'ANALYSIS_APPROVE')")
    public ResponseEntity<Map<String, Object>> validate(@PathVariable UUID jobId,
            @Valid @RequestBody TrainingCaseRequest request, @AuthenticationPrincipal Jwt jwt) {
        var expected = results.trainingDraft(jobId, UUID.fromString(jwt.getSubject())).caseDraft();
        var value = request.caseDraft();
        if (!Objects.equals(expected.get("caseId"), value.get("caseId"))
                || !(value.get("request") instanceof Map<?, ?> input)
                || !(value.get("snapshot") instanceof Map<?, ?> snapshot)
                || !Objects.equals(((Map<?, ?>) expected.get("request")).get("projectId"), input.get("projectId"))
                || !Objects.equals(((Map<?, ?>) expected.get("snapshot")).get("sourceRevision"), snapshot.get("sourceRevision"))
                || !Objects.equals(((Map<?, ?>) expected.get("snapshot")).get("requirementVersionId"), snapshot.get("requirementVersionId"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "학습 사례와 분석 작업의 연결 정보가 일치하지 않습니다.");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(transfers.validateCase(jobId, value));
    }

    /** 게시 없이 원본 커밋에서 누락된 근거를 확인해 검토할 학습 사례 사본을 반환한다. */
    @PostMapping("/api/analysis-jobs/{jobId}/training-case-evidence")
    @PreAuthorize("@permissionChecker.has(authentication, 'ANALYSIS_APPROVE')")
    public ResponseEntity<TrainingCaseDraftResponse> supplement(@PathVariable UUID jobId,
            @Valid @RequestBody TrainingCaseRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(evidence.supplement(jobId, UUID.fromString(jwt.getSubject()), request.caseDraft()));
    }
}
