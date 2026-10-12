package com.doenaai.analysis.service;

import com.doenaai.analysis.dto.AnalysisJobResponse;
import com.doenaai.analysis.repository.AnalysisResultRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisResultServiceTest {
    @Test void rejectsUnauthorizedAccessBeforeLookingUpResult() {
        var jobs = mock(AnalysisJobService.class);
        var results = mock(AnalysisResultRepository.class);
        var id = UUID.randomUUID();
        var user = UUID.randomUUID();
        when(jobs.getAnalysisJob(id, user)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> new AnalysisResultService(jobs, results).get(id, user))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(results);
    }

    @Test void pendingJobReturnsConflictNotAFakeResult() {
        var jobs = mock(AnalysisJobService.class);
        var results = mock(AnalysisResultRepository.class);
        var id = UUID.randomUUID();
        var user = UUID.randomUUID();
        when(jobs.getAnalysisJob(id, user)).thenReturn(AnalysisJobResponse.of(id, UUID.randomUUID(), null,
                "PENDING", 0, 0, 0, OffsetDateTime.now()));
        assertThatThrownBy(() -> new AnalysisResultService(jobs, results).get(id, user))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verifyNoInteractions(results);
    }
}
