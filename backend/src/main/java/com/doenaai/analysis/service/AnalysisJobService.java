package com.doenaai.analysis.service;

import com.doenaai.analysis.dto.AnalysisJobResponse;
import com.doenaai.analysis.repository.AnalysisJobRepository;
import com.doenaai.project.connection.dto.GitRepositoryConnectionResponse;
import com.doenaai.project.connection.service.GitRepositoryConnectionService;
import com.doenaai.project.service.ProjectService;
import com.doenaai.requirement.service.RequirementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AnalysisJobService {

    private final AnalysisJobRepository analysisJobRepository;
    private final ProjectService projectService;
    private final GitRepositoryConnectionService
            gitRepositoryConnectionService;
    private final RequirementService requirementService;

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
        projectService.assertCanManage(
                projectId,
                userId
        );

        if (requirementVersionId != null) {
            requirementService.assertVersionBelongsToProject(
                    requirementVersionId,
                    projectId,
                    userId
            );
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
}
