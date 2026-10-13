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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
        UUID jobId = job.jobId();
        log.info("Analysis started: jobId={}", jobId);
        String stage = "프로젝트 접근 권한 확인";
        long stageStartedNanos = startStage(jobId, stage);
        try {
            Map<String, Object> payload;
            if (job.requestedBy() == null) throw new IllegalStateException("Missing requesting user.");
            projects.assertCanManage(job.projectId(), job.requestedBy());
            completeStage(jobId, stage, stageStartedNanos);
            stage = "Git 커밋 소스 수집 (저장소 설정·인증·커밋과 파일 크기 제한 확인)";
            stageStartedNanos = startStage(jobId, stage);
            try (var snapshot = git.checkoutSnapshot(job.projectId(), job.requestedBy(), job.repositoryUrl(),
                    job.branch(), job.commitSha())) {
                completeStage(jobId, stage, stageStartedNanos);
                stage = "Git 수집 진행률 기록";
                jobs.progress(job.jobId(), 40, 0, 0);
                stage = "읽기 전용 DB 구조 수집 (저장된 연결·권한·스키마 확인)";
                stageStartedNanos = startStage(jobId, stage);
                Map<String, Object> databaseSummary = database.collect(job.projectId());
                completeStage(jobId, stage, stageStartedNanos);
                stage = "DB 수집 진행률 기록";
                jobs.progress(job.jobId(), 40, 100, 0);
                stage = "소스와 DB 참조 분석";
                stageStartedNanos = startStage(jobId, stage);
                var scan = source.collect(snapshot.path(), databaseSummary);
                var sourceSummary = new LinkedHashMap<>(scan.sourceSummary());
                sourceSummary.put("commitSha", job.commitSha());
                sourceSummary.put("analysisScope", job.requirementVersionId() == null
                        ? "PROJECT_STRUCTURE_SCAN" : "REQUIREMENT_IMPACT");
                completeStage(jobId, stage, stageStartedNanos);
                stage = "요구사항 버전 확인";
                stageStartedNanos = startStage(jobId, stage);
                addRequirement(job, sourceSummary);
                completeStage(jobId, stage, stageStartedNanos);
                stage = "소스 분석 진행률 기록";
                jobs.progress(job.jobId(), 100, 100, 40);
                stage = "AI 입력 준비 (분석 범위·민감 필드 확인)";
                stageStartedNanos = startStage(jobId, stage);
                payload = prompts.build(job.projectId(), sourceSummary, databaseSummary, scan.relations());
                completeStage(jobId, stage, stageStartedNanos);
                stage = "Git 임시 파일 정리";
                stageStartedNanos = startStage(jobId, stage);
            }
            completeStage(jobId, stage, stageStartedNanos);
            stage = "AI 분석 요청 (AI 서버·내부 API 키·모델·시간 제한 확인)";
            stageStartedNanos = startStage(jobId, stage);
            var result = ai.analyze(payload);
            completeStage(jobId, stage, stageStartedNanos);
            stage = "AI 분석 진행률 기록";
            jobs.progress(job.jobId(), 100, 100, 90);
            stage = "분석 결과 저장";
            stageStartedNanos = startStage(jobId, stage);
            // Persist exactly the facts the model saw, including any context truncation notice.
            Map<String, Object> facts = Map.of("sourceSummary", payload.get("sourceSummary"),
                    "databaseSummary", payload.get("databaseSummary"), "relations", payload.get("relations"));
            results.complete(job.jobId(), result, facts);
            completeStage(jobId, stage, stageStartedNanos);
            log.info("Analysis completed: jobId={}", job.jobId());
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            // JDBC/Git/HTTP exception messages can contain credentials. Do not log them or stack traces.
            log.warn("Analysis failed: jobId={}, stage={}, exception={}",
                    job.jobId(), stage, exception.getClass().getSimpleName());
            try {
                jobs.fail(job.jobId(), stage + " 단계에서 실패했습니다. 설정을 확인한 뒤 새 분석을 시작하세요.");
            } catch (Exception persistenceException) {
                log.error("Analysis failure status update failed: jobId={}, stage={}, exception={}",
                        job.jobId(), stage, persistenceException.getClass().getSimpleName());
            }
        }
    }

    private long startStage(UUID jobId, String stage) {
        log.info("Analysis stage started: jobId={}, stage={}", jobId, stage);
        return System.nanoTime();
    }

    private void completeStage(UUID jobId, String stage, long startedNanos) {
        log.info("Analysis stage completed: jobId={}, stage={}, duration_ms={}",
                jobId, stage, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
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
