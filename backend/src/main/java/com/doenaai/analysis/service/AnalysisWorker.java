package com.doenaai.analysis.service;

import com.doenaai.analysis.dto.AnalysisWorkItem;
import com.doenaai.analysis.repository.AnalysisJobRepository;
import com.doenaai.analysis.repository.AnalysisResultRepository;
import com.doenaai.project.connection.service.DatabaseMetadataCollector;
import com.doenaai.project.connection.service.GitRepositoryConnectionService;
import com.doenaai.project.service.ProjectService;
import com.doenaai.requirement.repository.RequirementVersionRepository;
import com.doenaai.requirement.service.RequirementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "doenaai.analysis.worker-enabled", havingValue = "true", matchIfMissing = true)
public class AnalysisWorker {
    private final AnalysisJobRepository jobs;
    private final AnalysisResultRepository results;
    private final ProjectService projects;
    private final GitRepositoryConnectionService git;
    private final DatabaseMetadataCollector database;
    private final SourceEvidenceCollector source;
    private final RequirementService requirements;
    private final RequirementVersionRepository versions;
    private final AnalysisPromptBuilder prompts;
    private final AiAnalysisClient ai;
    @Value("${doenaai.analysis.stale-timeout-seconds:1800}")
    private int staleTimeoutSeconds = 1800;
    @Value("${doenaai.ai-server.timeout-seconds:660}")
    private int aiTimeoutSeconds = 660;

    // One in-flight model call per Backend process; no transaction is held during IO.
    @Scheduled(fixedDelayString = "${doenaai.analysis.poll-delay-ms:2500}", initialDelayString = "5000")
    public void poll() {
        try {
            jobs.failStale(Math.max(staleTimeoutSeconds, aiTimeoutSeconds + 600));
            jobs.claimNext().ifPresent(this::process);
        } catch (Exception exception) {
            log.warn("Analysis queue poll failed: exception={}", exception.getClass().getSimpleName());
        }
    }

    void process(AnalysisWorkItem job) {
        String stage = "프로젝트 접근 권한 확인";
        try {
            Map<String, Object> payload;
            if (job.requestedBy() == null) throw new IllegalStateException("Missing requesting user.");
            projects.assertCanManage(job.projectId(), job.requestedBy());
            stage = "Git 커밋 소스 수집 (저장소 설정·인증·커밋과 파일 크기 제한 확인)";
            try (var snapshot = git.checkoutSnapshot(job.projectId(), job.requestedBy(), job.repositoryUrl(),
                    job.branch(), job.commitSha())) {
                jobs.progress(job.jobId(), 40, 0, 0);
                stage = "읽기 전용 DB 구조 수집 (저장된 연결·권한·스키마 확인)";
                Map<String, Object> databaseSummary = database.collect(job.projectId());
                jobs.progress(job.jobId(), 40, 100, 0);
                stage = "소스와 DB 참조 분석";
                var scan = source.collect(snapshot.path(), databaseSummary);
                var sourceSummary = new LinkedHashMap<>(scan.sourceSummary());
                sourceSummary.put("commitSha", job.commitSha());
                sourceSummary.put("analysisScope", job.requirementVersionId() == null
                        ? "PROJECT_STRUCTURE_SCAN" : "REQUIREMENT_IMPACT");
                stage = "요구사항 버전 확인";
                addRequirement(job, sourceSummary);
                jobs.progress(job.jobId(), 100, 100, 40);
                stage = "AI 입력 준비 (분석 범위·민감 필드 확인)";
                payload = prompts.build(job.projectId(), sourceSummary, databaseSummary, scan.relations());
                stage = "Git 임시 파일 정리";
            }
            stage = "AI 분석 요청 (AI 서버·내부 API 키·모델·시간 제한 확인)";
            var result = ai.analyze(payload);
            jobs.progress(job.jobId(), 100, 100, 90);
            stage = "분석 결과 저장";
            // Persist exactly the facts the model saw, including any context truncation notice.
            Map<String, Object> facts = Map.of("sourceSummary", payload.get("sourceSummary"),
                    "databaseSummary", payload.get("databaseSummary"), "relations", payload.get("relations"));
            results.complete(job.jobId(), result, facts);
            log.info("Analysis completed: jobId={}", job.jobId());
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            jobs.fail(job.jobId(), stage + " 단계에서 실패했습니다. 설정을 확인한 뒤 새 분석을 시작하세요.");
            // JDBC/Git/HTTP exception messages can contain credentials. Do not log them or stack traces.
            log.warn("Analysis failed: jobId={}, stage={}, exception={}",
                    job.jobId(), stage, exception.getClass().getSimpleName());
        }
    }

    private void addRequirement(AnalysisWorkItem job, Map<String, Object> summary) {
        if (job.requirementVersionId() == null) return;
        requirements.assertVersionBelongsToProject(job.requirementVersionId(), job.projectId(), job.requestedBy());
        var version = versions.findById(job.requirementVersionId())
                .orElseThrow(() -> new IllegalStateException("Requirement version no longer exists."));
        Map<String, Object> requirement = new LinkedHashMap<>();
        requirement.put("versionId", version.id().toString());
        requirement.put("title", version.title());
        requirement.put("refinedContent", version.refinedContent() == null ? "" : version.refinedContent());
        requirement.put("acceptanceCriteria", version.acceptanceCriteria());
        summary.put("changeRequirement", requirement);
    }
}
