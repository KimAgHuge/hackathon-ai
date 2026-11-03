package com.doenaai.analysis.service;

import com.doenaai.analysis.AnalysisNodeIdentity;
import com.doenaai.analysis.dto.AnalysisJobResponse;
import com.doenaai.analysis.dto.AnalysisRequirementContext;
import com.doenaai.analysis.repository.AnalysisJobRepository;
import com.doenaai.analysis.repository.AnalysisResultRepository;
import com.doenaai.project.connection.dto.GitRepositoryConnectionResponse;
import com.doenaai.project.connection.service.GitRepositoryConnectionService;
import com.doenaai.project.service.ProjectService;
import com.doenaai.requirement.service.RequirementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AnalysisJobService {

    private final AnalysisJobRepository analysisJobRepository;
    private final AnalysisResultRepository analysisResultRepository;
    private final ProjectService projectService;
    private final GitRepositoryConnectionService
            gitRepositoryConnectionService;
    private final RequirementService requirementService;
    private final ObjectProvider<AnalysisWorker> analysisWorker;
    private final AnalysisNodeIdentity nodeIdentity;

    /**
     * Git 연결과 접근 권한을 확인한 뒤 분석 작업을 대기열에 등록한다.
     * @param requirementVersionId null이면 전체 구조 분석, 값이 있으면 해당 변경 영향 분석
     * @return 생성된 작업 ID와 초기 진행 상태
     */
    public AnalysisJobResponse createProjectScan(
            UUID projectId,
            UUID requirementVersionId,
            UUID userId
    ) {
        return createProjectScan(projectId, requirementVersionId, userId, null, false);
    }

    public AnalysisJobResponse createProjectScan(
            UUID projectId, UUID requirementVersionId, UUID userId, UUID reuseFromJobId,
            boolean confirmedUnchanged
    ) {
        return createProjectScan(projectId, requirementVersionId, userId, reuseFromJobId, confirmedUnchanged, true);
    }

    /**
     * @param evidenceSelection AI 모델에 보낼 근거 방식. null·빈 값·AUTO는 기존 동작(근거가 크면 관련 근거만, 작으면 전체)이다.
     */
    public AnalysisJobResponse createProjectScan(
            UUID projectId, UUID requirementVersionId, UUID userId, UUID reuseFromJobId,
            boolean confirmedUnchanged, String evidenceSelection
    ) {
        return createProjectScan(projectId, requirementVersionId, userId, reuseFromJobId,
                confirmedUnchanged, true, "medium", evidenceSelection);
    }

    public AnalysisJobResponse createProjectScan(
            UUID projectId, UUID requirementVersionId, UUID userId, UUID reuseFromJobId,
            boolean confirmedUnchanged, boolean thinkingEnabled
    ) {
        return createProjectScan(projectId, requirementVersionId, userId, reuseFromJobId,
                confirmedUnchanged, thinkingEnabled, "medium");
    }

    public AnalysisJobResponse createProjectScan(
            UUID projectId, UUID requirementVersionId, UUID userId, UUID reuseFromJobId,
            boolean confirmedUnchanged, boolean thinkingEnabled, String thinkingEffort
    ) {
        return createProjectScan(projectId, requirementVersionId, userId, reuseFromJobId,
                confirmedUnchanged, thinkingEnabled, thinkingEffort, null);
    }

    public AnalysisJobResponse createProjectScan(
            UUID projectId, UUID requirementVersionId, UUID userId, UUID reuseFromJobId,
            boolean confirmedUnchanged, boolean thinkingEnabled, String thinkingEffort, String evidenceSelection
    ) {
        String selection = normalizeEvidenceSelection(evidenceSelection);
        if (thinkingEffort == null) thinkingEffort = "medium";
        if (!java.util.Set.of("low", "medium", "high").contains(thinkingEffort)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "추론 강도는 low, medium, high 중에서 선택해야 합니다.");
        }
        projectService.assertCanManage(
                projectId,
                userId
        );
        nodeIdentity.requireLocalWorker();

        if (requirementVersionId != null) {
            requirementService.assertVersionBelongsToProject(
                    requirementVersionId,
                    projectId,
                    userId
            );
        }

        if (reuseFromJobId != null) {
            if (!confirmedUnchanged) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "선택한 시점의 저장 근거를 사용한다는 확인이 필요합니다.");
            }
            var evidence = analysisResultRepository.findReusableEvidenceByJobId(projectId, reuseFromJobId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "선택한 분석의 저장 근거가 없거나 재사용할 수 없습니다. 최신 Git·DB 확인으로 다시 수집하세요."));
            if (!EvidenceReusePolicy.canUseSavedSnapshot(evidence,
                    evidence.sourceSummary() != null && evidence.sourceSummary().get("commitSha") instanceof String sha ? sha : null)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "저장된 소스·DB 근거의 필수 구조나 커밋 정보를 확인할 수 없습니다. 최신 Git·DB 확인으로 다시 수집하세요.");
            }
            if (selection != null) {
                return analysisJobRepository.createFromSavedEvidence(projectId, requirementVersionId,
                        userId, reuseFromJobId, thinkingEnabled, thinkingEffort, selection);
            }
            return analysisJobRepository.createFromSavedEvidence(projectId, requirementVersionId,
                    userId, reuseFromJobId, thinkingEnabled, thinkingEffort);
        }

        GitRepositoryConnectionResponse repository =
                gitRepositoryConnectionService
                        .verifySavedProject(
                                projectId,
                                userId
                        );

        if (!repository.success()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    repository.message()
            );
        }

        if (selection != null) {
            return analysisJobRepository.createProjectScan(
                    projectId,
                    requirementVersionId,
                    userId,
                    repository.repositoryUrl(),
                    repository.branch(),
                    repository.commitSha(),
                    thinkingEnabled,
                    thinkingEffort,
                    selection
            );
        }
        return analysisJobRepository.createProjectScan(
                projectId,
                requirementVersionId,
                userId,
                repository.repositoryUrl(),
                repository.branch(),
                repository.commitSha(),
                thinkingEnabled,
                thinkingEffort
        );
    }

    /** 허용하지 않는 값은 거절하고, 기본 동작(AUTO)은 null로 돌려 기존 경로를 그대로 쓴다. */
    private static String normalizeEvidenceSelection(String value) {
        if (value == null || value.isBlank() || "AUTO".equals(value)) return null;
        if ("ALWAYS".equals(value) || "OFF".equals(value)) return value;
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "근거 전달 방식은 AUTO, ALWAYS, OFF 중 하나여야 합니다.");
    }

    /** 작업 ID와 사용자 ID로 접근 권한을 확인하고 최신 진행 상태를 반환한다. */
    @Transactional(readOnly = true)
    public AnalysisJobResponse getAnalysisJob(
            UUID analysisJobId,
            UUID userId
    ) {
        AnalysisJobResponse analysisJob =
                analysisJobRepository
                        .findById(analysisJobId)
                        .orElseThrow(() ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "분석 작업을 찾을 수 없습니다."
                                )
                        );

        projectService.getProject(
                analysisJob.projectId(),
                userId
        );

        if (analysisJob.requirementVersionId() == null) return analysisJob;
        try {
            var version = requirementService.assertVersionBelongsToProject(
                    analysisJob.requirementVersionId(), analysisJob.projectId(), userId);
            return version == null ? analysisJob : analysisJob.withRequirementContext(AnalysisRequirementContext.from(version));
        } catch (ResponseStatusException exception) {
            // 과거 버전을 찾지 못해도 접근 가능한 작업의 진행 상태는 계속 표시한다.
            if (exception.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) return analysisJob;
            throw exception;
        }
    }

    /** 화면을 떠나는 동작과 달리 서버 작업을 취소하고 같은 인스턴스의 실행 중 요청 중단을 시도한다. */
    public AnalysisJobResponse cancelAnalysisJob(UUID analysisJobId, UUID userId) {
        var current = getAnalysisJob(analysisJobId, userId);
        projectService.assertCanManage(current.projectId(), userId);
        if (analysisJobRepository.cancel(analysisJobId)) {
            analysisWorker.ifAvailable(worker -> worker.interruptActive(analysisJobId));
        }
        return analysisJobRepository.findById(analysisJobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "분석 작업을 찾을 수 없습니다."))
                .withRequirementContext(current.requirementContext());
    }
}
