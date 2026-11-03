package com.doenaai.analysis.service;

import com.doenaai.analysis.dto.AnalysisHistoryPage;
import com.doenaai.analysis.repository.AnalysisHistoryRepository;
import com.doenaai.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AnalysisHistoryService {
    private final ProjectService projects;
    private final AnalysisHistoryRepository history;

    /** 사용자에게 보이는 프로젝트만 조회하고 선택 프로젝트도 같은 접근 검사를 통과시킨다. */
    @Transactional(readOnly = true)
    public AnalysisHistoryPage list(UUID userId, UUID projectId, int page, int size) {
        return list(userId, projectId, page, size, false);
    }

    /** 저장 자료 목록도 같은 프로젝트 접근 검사와 페이지 제한을 적용한다. */
    @Transactional(readOnly = true)
    public AnalysisHistoryPage list(UUID userId, UUID projectId, int page, int size, boolean reusableEvidenceOnly) {
        List<UUID> ids = projectId == null
                ? projects.getProjects(userId).stream().map(project -> project.id()).toList()
                : List.of(projects.getProject(projectId, userId).id());
        return reusableEvidenceOnly ? history.find(ids, page, size, true) : history.find(ids, page, size);
    }
}
