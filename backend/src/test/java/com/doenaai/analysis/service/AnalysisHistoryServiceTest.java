package com.doenaai.analysis.service;

import com.doenaai.analysis.dto.AnalysisHistoryPage;
import com.doenaai.analysis.repository.AnalysisHistoryRepository;
import com.doenaai.project.dto.ProjectResponse;
import com.doenaai.project.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AnalysisHistoryServiceTest {
    private final ProjectService projects = mock(ProjectService.class);
    private final AnalysisHistoryRepository history = mock(AnalysisHistoryRepository.class);
    private final AnalysisHistoryService service = new AnalysisHistoryService(projects, history);

    @Test void listWithoutFilterUsesOnlyVisibleProjects() {
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        when(projects.getProjects(userId)).thenReturn(List.of(project(projectId)));
        var response = new AnalysisHistoryPage(List.of(), 0, 0, 10);
        when(history.find(List.of(projectId), 0, 10)).thenReturn(response);

        assertThat(service.list(userId, null, 0, 10)).isSameAs(response);
        verify(history).find(List.of(projectId), 0, 10);
    }

    @Test void inaccessibleProjectStopsBeforeHistoryQuery() {
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        when(projects.getProject(projectId, userId)).thenThrow(new ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다."));

        assertThatThrownBy(() -> service.list(userId, projectId, 0, 10))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(history);
    }

    private ProjectResponse project(UUID id) {
        return new ProjectResponse(id, UUID.randomUUID(), "테스트 프로젝트", null, null,
                null, null, null, null, null, null, null);
    }
}
