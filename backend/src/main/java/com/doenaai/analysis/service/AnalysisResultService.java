package com.doenaai.analysis.service;

import com.doenaai.analysis.dto.AnalysisResultResponse;
import com.doenaai.analysis.repository.AnalysisResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AnalysisResultService {
    private final AnalysisJobService jobs;
    private final AnalysisResultRepository results;

    /** 완료된 jobId의 저장 결과를 반환한다; userId에 해당 프로젝트 조회 권한이 필요하다. */
    public AnalysisResultResponse get(UUID jobId, UUID userId) {
        var job = jobs.getAnalysisJob(jobId, userId); // Same project/tenant access check as progress.
        if (!"COMPLETED".equals(job.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "아직 완료되지 않은 분석입니다.");
        }
        return results.findByJobId(jobId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "분석 결과를 찾을 수 없습니다."));
    }
}
