package com.doenaai.analysis.service;

import com.doenaai.analysis.AnalysisNodeIdentity;
import com.doenaai.analysis.dto.AnalysisJobResponse;
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
                        "저장된 근거 사용에는 Git·DB 변경 없음 확인이 필요합니다.");
            }
            var evidence = analysisResultRepository.findReusableEvidenceByJobId(projectId, reuseFromJobId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "선택한 분석의 저장 근거가 없거나 재사용할 수 없습니다. 최신 Git·DB 확인으로 다시 수집하세요."));
            if (!EvidenceReusePolicy.canUseSavedSnapshot(evidence,
                    (String) evidence.sourceSummary().get("commitSha"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "저장된 소스·DB 근거가 완전하지 않거나 수집 방식이 이전 버전입니다. 최신 Git·DB 확인으로 다시 수집하세요.");
            }
            return analysisJobRepository.createFromSavedEvidence(projectId, requirementVersionId,
                    userId, reuseFromJobId);
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

        return analysisJobRepository.createProjectScan(
                projectId,
                requirementVersionId,
                userId,
                repository.repositoryUrl(),
                repository.branch(),
                repository.commitSha()
        );
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

        return analysisJob;
    }

    /** 화면을 떠나는 동작과 달리 서버 작업을 취소하고 같은 인스턴스의 실행 중 요청 중단을 시도한다. */
    public AnalysisJobResponse cancelAnalysisJob(UUID analysisJobId, UUID userId) {
        var current = getAnalysisJob(analysisJobId, userId);
        projectService.assertCanManage(current.projectId(), userId);
        if (analysisJobRepository.cancel(analysisJobId)) {
            analysisWorker.ifAvailable(worker -> worker.interruptActive(analysisJobId));
        }
        return analysisJobRepository.findById(analysisJobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "분석 작업을 찾을 수 없습니다."));
    }
}
