package com.doenaai.analysis.service;

import com.doenaai.analysis.AnalysisNodeIdentity;
import com.doenaai.analysis.dto.AnalysisWorkItem;
import com.doenaai.analysis.repository.AnalysisJobRepository;
import com.doenaai.analysis.repository.AnalysisResultRepository;
import com.doenaai.project.connection.service.DatabaseMetadataCollector;
import com.doenaai.project.connection.service.GitRepositoryConnectionService;
import com.doenaai.project.service.ProjectService;
import com.doenaai.requirement.repository.RequirementVersionRepository;
import com.doenaai.requirement.service.RequirementService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 대기 작업을 가져와 Git·DB 근거 수집, AI 호출, 결과 저장까지 순서대로 처리한다. */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "doenaai.analysis.worker-enabled", havingValue = "true")
public class AnalysisWorker {
    private static final int MAX_CLARIFICATION_CONTEXT_CHARS = 12_000;
    private static final ScheduledExecutorService ACTIVITY_HEARTBEATS =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "analysis-activity-heartbeat");
                thread.setDaemon(true);
                return thread;
            });
    // 화면에는 고정된 안전 문구만 노출한다. 예외 메시지·접속 정보·원본 서버 로그는 전달하지 않는다.
    private static final Map<String, String> PUBLIC_STAGES = Map.ofEntries(
            Map.entry("프로젝트 접근 권한 확인", "프로젝트 접근 권한 확인"),
            Map.entry("선택한 저장 근거 확인", "선택한 이전 분석 근거 확인"),
            Map.entry("기존 소스·DB 근거 재사용 가능 여부 확인", "이전 분석 근거 재사용 가능 여부 확인"),
            Map.entry("읽기 전용 DB 구조 수집 (저장된 연결·권한·스키마 확인)", "데이터베이스 구조 수집"),
            Map.entry("저장된 근거에 현재 요구사항 적용", "저장된 근거에 현재 요구사항 반영"),
            Map.entry("Git 커밋 소스 수집 (저장소 설정·인증·커밋과 파일 크기 제한 확인)", "Git 소스코드 수집"),
            Map.entry("소스와 DB 참조 분석", "소스코드와 데이터베이스 관계 조사"),
            Map.entry("요구사항 버전 확인", "분석할 요구사항 확인"),
            Map.entry("AI 입력 준비 (분석 범위·민감 필드 확인)", "AI 분석 자료 준비"),
            Map.entry("AI 분석 요청 (AI 분석/추론 서버·내부 API 키·모델·시간 제한 확인)", "AI 모델의 분석 응답 대기"),
            Map.entry("분석 결과 저장", "분석 결과 저장")
    );
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
    private final AnalysisNodeIdentity nodeIdentity;
    private final ConcurrentMap<UUID, Thread> activeThreads = new ConcurrentHashMap<>();
    @Value("${doenaai.analysis.stale-timeout-seconds:4500}")
    private int staleTimeoutSeconds = 4500;
    @Value("${doenaai.ai-server.timeout-seconds:3900}")
    private int aiTimeoutSeconds = 3900;

    @PostConstruct
    void logWorkerReady() {
        log.info("Analysis worker ready: instanceId={}", nodeIdentity.instanceId());
    }

    /** 대기 작업을 하나 선점한다. 원격 호출 중에는 DB 트랜잭션을 유지하지 않는다. */
    @Scheduled(fixedDelayString = "${doenaai.analysis.poll-delay-ms:2500}", initialDelayString = "5000")
    public void poll() {
        try {
            jobs.failStale(Math.max(staleTimeoutSeconds, aiTimeoutSeconds + 600));
            jobs.claimNext().ifPresent(this::process);
        } catch (Exception exception) {
            log.warn("Analysis queue poll failed: exception={}", exception.getClass().getSimpleName());
        }
    }

    /** 선점한 job을 처리해 완료 결과 또는 안전한 실패 사유를 저장한다. */
    void process(AnalysisWorkItem job) {
        UUID jobId = job.jobId();
        activeThreads.put(jobId, Thread.currentThread());
        log.info("Analysis started: jobId={}, instanceId={}", jobId, nodeIdentity.instanceId());
        String stage = "프로젝트 접근 권한 확인";
        long stageStartedNanos = startStage(jobId, stage);
        try {
            if (jobs.isCancelled(jobId)) throw new CancellationException("Analysis cancelled.");
            Map<String, Object> payload = null;
            Map<String, Object> databaseSummary = null;
            if (job.requestedBy() == null) throw new IllegalStateException("Missing requesting user.");
            projects.assertCanManage(job.projectId(), job.requestedBy());
            completeStage(jobId, stage, stageStartedNanos);
            if (job.reuseFromJobId() != null) {
                stage = "선택한 저장 근거 확인";
                stageStartedNanos = startStage(jobId, stage);
                var saved = results.findReusableEvidenceByJobId(job.projectId(), job.reuseFromJobId())
                        .orElseThrow(() -> new IllegalStateException("저장 근거를 재사용할 수 없습니다. 최신 Git·DB 확인으로 다시 수집하세요."));
                if (!EvidenceReusePolicy.canUseSavedSnapshot(saved, job.commitSha())) {
                    throw new IllegalStateException("저장 근거의 필수 구조나 커밋 정보를 확인할 수 없습니다.");
                }
                databaseSummary = saved.databaseSummary();
                var sourceSummary = new LinkedHashMap<>(saved.sourceSummary());
                sourceSummary.remove("changeRequirement");
                var savedWarnings = EvidenceReusePolicy.savedSnapshotWarnings(saved);
                sourceSummary.put("savedEvidence", Map.of("sourceJobId", saved.sourceJobId().toString(),
                        "currentStateVerified", false, "warnings", savedWarnings));
                var savedCoverage = sourceSummary.get("promptCoverage") instanceof Map<?, ?> existing
                        ? new LinkedHashMap<String, Object>((Map<String, Object>) existing) : new LinkedHashMap<String, Object>();
                savedCoverage.put("upstreamCoverageUnknown", savedWarnings.contains("SOURCE_PROMPT_COVERAGE_MISSING")
                        || Boolean.TRUE.equals(savedCoverage.get("upstreamCoverageUnknown")));
                sourceSummary.put("promptCoverage", savedCoverage);
                sourceSummary.put("analysisScope", job.requirementVersionId() == null
                        ? "PROJECT_STRUCTURE_SCAN" : "REQUIREMENT_IMPACT");
                addRequirement(job, sourceSummary);
                jobs.progress(jobId, 100, 100, 40);
                payload = prompts.build(job.projectId(), sourceSummary, databaseSummary, saved.relations());
                completeStage(jobId, stage, stageStartedNanos);
                log.info("Saved analysis evidence selected: jobId={}, sourceJobId={}", jobId, saved.sourceJobId());
            } else {
                stage = "기존 소스·DB 근거 재사용 가능 여부 확인";
                stageStartedNanos = startStage(jobId, stage);
                var previous = results.findReusableEvidence(job.projectId(), job.commitSha());
                completeStage(jobId, stage, stageStartedNanos);
                if (previous.isPresent()) {
                stage = "읽기 전용 DB 구조 수집 (저장된 연결·권한·스키마 확인)";
                stageStartedNanos = startStage(jobId, stage);
                databaseSummary = database.collect(job.projectId());
                completeStage(jobId, stage, stageStartedNanos);
                if (EvidenceReusePolicy.canReuse(previous.get(), databaseSummary, job.commitSha())) {
                    stage = "저장된 근거에 현재 요구사항 적용";
                    stageStartedNanos = startStage(jobId, stage);
                    var sourceSummary = new LinkedHashMap<>(previous.get().sourceSummary());
                    sourceSummary.remove("changeRequirement");
                    sourceSummary.remove("promptCoverage");
                    sourceSummary.put("analysisScope", job.requirementVersionId() == null
                            ? "PROJECT_STRUCTURE_SCAN" : "REQUIREMENT_IMPACT");
                    addRequirement(job, sourceSummary);
                    jobs.progress(jobId, 100, 100, 40);
                    payload = prompts.build(job.projectId(), sourceSummary, databaseSummary, previous.get().relations());
                    jobs.markEvidenceReused(jobId, previous.get().sourceJobId());
                    completeStage(jobId, stage, stageStartedNanos);
                    log.info("Analysis evidence reused: jobId={}, sourceJobId={}", jobId, previous.get().sourceJobId());
                }
                }
            }
            if (payload == null) {
                stage = "Git 커밋 소스 수집 (저장소 설정·인증·커밋과 파일 크기 제한 확인)";
                stageStartedNanos = startStage(jobId, stage);
                try (var snapshot = git.checkoutSnapshot(job.projectId(), job.requestedBy(), job.repositoryUrl(),
                        job.branch(), job.commitSha())) {
                    completeStage(jobId, stage, stageStartedNanos);
                    stage = "Git 수집 진행률 기록";
                    jobs.progress(job.jobId(), 40, 0, 0);
                    if (databaseSummary == null) {
                        stage = "읽기 전용 DB 구조 수집 (저장된 연결·권한·스키마 확인)";
                        stageStartedNanos = startStage(jobId, stage);
                        databaseSummary = database.collect(job.projectId());
                        completeStage(jobId, stage, stageStartedNanos);
                    }
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
                    // Git 수집뿐 아니라 소스와 DB 참조 조사까지 끝난 시점에 소스 단계를 완료로 기록한다.
                    jobs.progress(job.jobId(), 100, 100, 0);
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
            }
            payload = new LinkedHashMap<>(payload);
            payload.put("thinkingEnabled", job.thinkingEnabled());
            payload.put("thinkingEffort", job.thinkingEffort());
            stage = "AI 분석 요청 (AI 분석/추론 서버·내부 API 키·모델·시간 제한 확인)";
            stageStartedNanos = startStage(jobId, stage);
            if (jobs.isCancelled(jobId)) throw new CancellationException("Analysis cancelled.");
            // 모델 내부 완료율을 추정하지 않고, 긴 HTTP 대기 중 백엔드의 대기 상태만 알린다.
            var waiting = ACTIVITY_HEARTBEATS.scheduleAtFixedRate(
                    () -> recordPublicActivity(jobId, "AI 분석 요청 (AI 분석/추론 서버·내부 API 키·모델·시간 제한 확인)", "WAITING"),
                    30, 30, TimeUnit.SECONDS);
            Map<String, Object> result;
            try {
                result = ai.analyzeWithProgress(payload, event -> recordAiProgress(jobId, event));
            } finally {
                waiting.cancel(false);
            }
            if (result.get("analysisThinkingEnabled") instanceof Boolean reportedThinking
                    && job.thinkingEnabled() != null && !job.thinkingEnabled().equals(reportedThinking)) {
                throw new AiAnalysisClient.ModelFailureException("THINKING_MODE_MISMATCH");
            }
            var reportedEffort = result.get("analysisThinkingEffort");
            if (reportedEffort != null && (!(reportedEffort instanceof String effort)
                    || !List.of("low", "medium", "high").contains(effort)
                    || job.thinkingEffort() != null && !job.thinkingEffort().equals(effort))) {
                throw new AiAnalysisClient.ModelFailureException("THINKING_EFFORT_MISMATCH");
            }
            completeStage(jobId, stage, stageStartedNanos);
            stage = "AI 분석 진행률 기록";
            jobs.progress(job.jobId(), 100, 100, 90);
            stage = "분석 결과 저장";
            stageStartedNanos = startStage(jobId, stage);
            // 실제 모델 입력과 원래 일반 분석 응답을 보존하되 최종 검토 결과의 형식은 유지한다.
            Map<String, Object> facts = new LinkedHashMap<>(Map.of("sourceSummary", payload.get("sourceSummary"),
                    "databaseSummary", payload.get("databaseSummary"), "relations", payload.get("relations")));
            facts.put("thinkingEnabled", job.thinkingEnabled());
            facts.put("thinkingEffort", job.thinkingEffort());
            var finalResult = new LinkedHashMap<>(result);
            var analysisThinking = finalResult.remove("analysisThinkingEnabled");
            if (analysisThinking instanceof Boolean enabled) facts.put("analysisThinkingEnabled", enabled);
            var analysisEffort = finalResult.remove("analysisThinkingEffort");
            if (analysisEffort instanceof String effort) facts.put("analysisThinkingEffort", effort);
            var analysisModel = finalResult.remove("analysisModel");
            if (analysisModel instanceof String name && name.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,199}")) facts.put("analysisModel", name);
            var sourceModel = finalResult.remove("analysisSourceModel");
            if (sourceModel instanceof String name && name.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,199}")) facts.put("analysisSourceModel", name);
            var adapterDigest = finalResult.remove("analysisAdapterDigest");
            if (adapterDigest instanceof String hash && hash.matches("[0-9a-f]{64}")) facts.put("analysisAdapterDigest", hash);
            var trainedModelDigest = finalResult.remove("analysisTrainedModelDigest");
            if (trainedModelDigest instanceof String hash && hash.matches("[0-9a-f]{64}")) facts.put("analysisTrainedModelDigest", hash);
            var generalAnalysis = finalResult.remove("generalAnalysis");
            if (generalAnalysis instanceof Map<?, ?>) facts.put("generalAnalysis", generalAnalysis);
            results.complete(job.jobId(), finalResult, facts);
            completeStage(jobId, stage, stageStartedNanos);
            log.info("Analysis completed: jobId={}", job.jobId());
        } catch (Exception exception) {
            boolean interrupted = Thread.interrupted();
            boolean cancelled = exception instanceof CancellationException;
            if (!cancelled) {
                try {
                    cancelled = jobs.isCancelled(jobId);
                } catch (Exception statusFailure) {
                    log.warn("Analysis cancellation status check failed: jobId={}, exception={}",
                            jobId, statusFailure.getClass().getSimpleName());
                }
            }
            if (cancelled) {
                log.info("Analysis cancelled: jobId={}", jobId);
                return;
            }
            // JDBC/Git/HTTP exception messages can contain credentials. Do not log them or stack traces.
            var cleanupFailure = exception instanceof GitRepositoryConnectionService.SnapshotCleanupException classified
                    ? classified : null;
            var modelFailure = exception instanceof AiAnalysisClient.ModelFailureException classified ? classified : null;
            String failureCode = cleanupFailure != null ? cleanupFailure.code()
                    : modelFailure != null ? modelFailure.code()
                    : "Git 임시 파일 정리".equals(stage) ? "GIT_CLEANUP_UNCLASSIFIED" : "ANALYSIS_STAGE_FAILURE";
            String failureArea = cleanupFailure == null ? "NOT_APPLICABLE" : cleanupFailure.area();
            log.warn("Analysis failed: jobId={}, stage={}, exception={}, code={}, area={}, instanceId={}",
                    job.jobId(), stage, exception.getClass().getSimpleName(), failureCode, failureArea, nodeIdentity.instanceId());
            try {
                jobs.fail(job.jobId(), stage + " 단계에서 실패했습니다. 오류 코드: " + failureCode
                        + (modelFailure == null ? "" : ". " + modelFailure.getMessage())
                        + (cleanupFailure == null ? "" : ", 정리 영역: " + failureArea)
                        + ", 처리 인스턴스: " + nodeIdentity.instanceId() + ".");
            } catch (Exception persistenceException) {
                log.error("Analysis failure status update failed: jobId={}, stage={}, exception={}",
                        job.jobId(), stage, persistenceException.getClass().getSimpleName());
            }
            if (interrupted || exception instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            activeThreads.remove(jobId, Thread.currentThread());
        }
    }

    /** 취소된 실행 작업의 Git/HTTP 대기를 깨운다. DB 상태가 우선이며 결과 저장은 상태 조건으로 차단된다. */
    public void interruptActive(UUID jobId) {
        Thread thread = activeThreads.get(jobId);
        if (thread != null) thread.interrupt();
    }

    private long startStage(UUID jobId, String stage) {
        log.info("Analysis stage started: jobId={}, stage={}", jobId, stage);
        recordPublicActivity(jobId, stage, "STARTED");
        return System.nanoTime();
    }

    private void completeStage(UUID jobId, String stage, long startedNanos) {
        log.info("Analysis stage completed: jobId={}, stage={}, duration_ms={}",
                jobId, stage, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
        // 결과 저장 완료 내역은 상태/결과와 원자적으로 저장되어야 최종 조회에서 누락되지 않는다.
        if (!"분석 결과 저장".equals(stage)) recordPublicActivity(jobId, stage, "COMPLETED");
    }

    private void recordPublicActivity(UUID jobId, String stage, String state) {
        String label = PUBLIC_STAGES.get(stage);
        if (label == null) return;
        try {
            jobs.appendActivity(jobId, label, state);
        } catch (RuntimeException exception) {
            // 표시용 내역 기록 장애 때문에 실제 분석을 실패시키지 않는다.
            log.warn("Analysis activity update failed: jobId={}, exception={}",
                    jobId, exception.getClass().getSimpleName());
        }
    }

    private void recordAiProgress(UUID jobId, AiAnalysisClient.ProgressEvent event) {
        String label;
        String state;
        switch (event.code()) {
            case "GENERAL_STARTED", "GENERAL_COMPLETED" -> {
                label = "AI 일반 영향 분석";
                state = event.code().endsWith("STARTED") ? "STARTED" : "COMPLETED";
            }
            case "DATABASE_STARTED", "DATABASE_COMPLETED" -> {
                label = "AI 데이터베이스 영향 분석";
                state = event.code().endsWith("STARTED") ? "STARTED" : "COMPLETED";
            }
            case "DATABASE_SKIPPED" -> {
                label = "추가 DB 분석 대상 아님";
                state = "COMPLETED";
            }
            case "DATABASE_SCOPE_FOCUSED", "DATABASE_SCOPE_FULL" -> {
                label = "DATABASE_SCOPE_FOCUSED".equals(event.code()) ? "요구사항 관련 DB 범위 우선 검토" : "수집한 전체 DB 범위 검토";
                if (event.attempt() != null) label += " (" + event.attempt() + "개 테이블)";
                state = "COMPLETED";
            }
            case "GENERAL_REUSED", "DATABASE_REUSED" -> {
                label = "GENERAL_REUSED".equals(event.code()) ? "저장된 AI 일반 분석 불러오기" : "저장된 AI DB 분석 불러오기";
                state = "COMPLETED";
            }
            case "RECONCILIATION_STARTED", "RECONCILIATION_COMPLETED", "RECONCILIATION_REUSED" -> {
                label = "일반·DB 분석 결과 최종 정리";
                if (event.code().endsWith("REUSED")) label += " · 저장 결과 재사용";
                state = event.code().endsWith("STARTED") ? "STARTED" : "COMPLETED";
            }
            case "DATABASE_BATCH_STARTED", "DATABASE_BATCH_COMPLETED", "DATABASE_BATCH_REUSED" -> {
                label = "AI DB 분할 검토" + (event.attempt() == null ? "" : " (" + event.attempt() + "번째 묶음)");
                if (event.code().endsWith("REUSED")) label += " · 저장 결과 재사용";
                state = event.code().endsWith("STARTED") ? "STARTED" : "COMPLETED";
            }
            case "DATABASE_MERGE_STARTED", "DATABASE_MERGE_COMPLETED", "DATABASE_MERGE_REUSED" -> {
                label = "AI DB 분할 결과 종합" + (event.attempt() == null ? "" : " (" + event.attempt() + "번째 묶음)");
                if (event.code().endsWith("REUSED")) label += " · 저장 결과 재사용";
                state = event.code().endsWith("STARTED") ? "STARTED" : "COMPLETED";
            }
            case "VALIDATION_RETRY" -> {
                label = "AI 응답 형식 재검증" + (event.attempt() == null ? "" : " (" + event.attempt() + "번째 시도)");
                state = "STARTED";
            }
            default -> { return; }
        }
        try {
            jobs.appendActivity(jobId, label, state);
        } catch (RuntimeException exception) {
            log.warn("Analysis AI activity update failed: jobId={}, exception={}",
                    jobId, exception.getClass().getSimpleName());
        }
    }

    /** 요구사항 버전이 있으면 소유 프로젝트를 검증하고 AI 입력에 변경 내용을 추가한다. */
    private void addRequirement(AnalysisWorkItem job, Map<String, Object> summary) {
        if (job.requirementVersionId() == null) return;
        requirements.assertVersionBelongsToProject(job.requirementVersionId(), job.projectId(), job.requestedBy());
        var version = versions.findById(job.requirementVersionId())
                .orElseThrow(() -> new IllegalStateException("Requirement version no longer exists."));
        Map<String, Object> requirement = new LinkedHashMap<>();
        requirement.put("requirementId", version.requirementId().toString());
        requirement.put("versionId", version.id().toString());
        requirement.put("title", version.title());
        requirement.put("refinedContent", version.refinedContent() == null ? "" : version.refinedContent());
        requirement.put("acceptanceCriteria", version.acceptanceCriteria());
        // 모든 확인 내용은 버전에 보존한다. 모델 입력에는 최근 내용을 한도 내에서만 전달한다.
        List<Object> selectedClarifications = new ArrayList<>();
        int selectedChars = 0;
        List<Object> allClarifications = version.clarifications();
        for (int index = allClarifications.size() - 1; index >= 0; index--) {
            Object item = allClarifications.get(index);
            int itemChars = item.toString().length();
            if (selectedChars + itemChars > MAX_CLARIFICATION_CONTEXT_CHARS) break;
            selectedClarifications.add(item);
            selectedChars += itemChars;
        }
        Collections.reverse(selectedClarifications);
        requirement.put("clarifications", selectedClarifications);
        requirement.put("clarificationCoverage", Map.of(
                "total", allClarifications.size(),
                "included", selectedClarifications.size(),
                "omitted", allClarifications.size() - selectedClarifications.size()));
        if (version.sourceAnalysisJobId() != null) {
            requirement.put("sourceAnalysisJobId", version.sourceAnalysisJobId().toString());
            version.clarifications().stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .filter(item -> "USER_QUESTION".equals(item.get("kind"))
                            && version.sourceAnalysisJobId().toString().equals(item.get("sourceAnalysisJobId")))
                    .reduce((previous, latest) -> latest)
                    .ifPresent(item -> requirement.put("latestFollowUpQuestion", item.get("question")));
        }
        // 화면에서 근거 전달 방식을 직접 고른 경우에만 AI 서버로 알린다. 기본(AUTO)은 값을 보내지 않는다.
        if ("ALWAYS".equals(job.evidenceSelection()) || "OFF".equals(job.evidenceSelection())) {
            requirement.put("evidenceSelection", job.evidenceSelection());
        }
        summary.put("changeRequirement", requirement);
    }
}
