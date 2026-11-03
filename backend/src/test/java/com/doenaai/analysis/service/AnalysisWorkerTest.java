package com.doenaai.analysis.service;

import com.doenaai.analysis.AnalysisNodeIdentity;
import com.doenaai.analysis.SourceEvidenceVersion;
import com.doenaai.analysis.dto.AnalysisWorkItem;
import com.doenaai.analysis.dto.ReusableEvidence;
import com.doenaai.analysis.repository.AnalysisJobRepository;
import com.doenaai.analysis.repository.AnalysisResultRepository;
import com.doenaai.project.connection.service.DatabaseMetadataCollector;
import com.doenaai.project.connection.service.GitRepositoryConnectionService;
import com.doenaai.project.service.ProjectService;
import com.doenaai.requirement.repository.RequirementVersionRepository;
import com.doenaai.requirement.service.RequirementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class AnalysisWorkerTest {
    @TempDir Path directory;
    final AnalysisJobRepository jobs = mock(AnalysisJobRepository.class);
    final AnalysisResultRepository results = mock(AnalysisResultRepository.class);
    final ProjectService projects = mock(ProjectService.class);
    final GitRepositoryConnectionService git = mock(GitRepositoryConnectionService.class);
    final DatabaseMetadataCollector database = mock(DatabaseMetadataCollector.class);
    final SourceEvidenceCollector source = mock(SourceEvidenceCollector.class);
    final RequirementService requirements = mock(RequirementService.class);
    final RequirementVersionRepository versions = mock(RequirementVersionRepository.class);
    final AiAnalysisClient ai = mock(AiAnalysisClient.class);
    final AnalysisNodeIdentity nodeIdentity = new AnalysisNodeIdentity(true, "test-backend-instance");
    final AnalysisWorkItem job = new AnalysisWorkItem(UUID.randomUUID(), UUID.randomUUID(), null,
            UUID.randomUUID(), "https://github.com/example/repository.git", "main", "a".repeat(40));
    AnalysisWorker worker;
    Path checkout;

    @BeforeEach void setup() throws Exception {
        worker = new AnalysisWorker(jobs, results, projects, git, database, source, requirements, versions,
                new AnalysisPromptBuilder(JsonMapper.builder().build(), AnalysisPromptBuilder.DEFAULT_MAX_CHARS),
                ai, nodeIdentity);
        checkout = Files.createDirectory(directory.resolve("checkout"));
        when(git.checkoutSnapshot(job.projectId(), job.requestedBy(), job.repositoryUrl(), job.branch(), job.commitSha()))
                .thenReturn(new GitRepositoryConnectionService.GitSnapshot(checkout, checkout));
    }

    @Test void onlyReceivingBackendInstancePollsItsOwnQueue() {
        worker.logWorkerReady();
        worker.poll();
        verify(jobs).failStale(4500);
        verify(jobs).claimNext();
    }

    @Test void collectsRealFactsCompletesAndDeletesCheckoutBeforeModelCall(CapturedOutput output) throws Exception {
        var db = Map.<String, Object>of("type", "POSTGRESQL", "tables", List.of());
        when(database.collect(job.projectId())).thenReturn(db);
        when(source.collect(checkout, db)).thenReturn(new SourceEvidenceCollector.ScanResult(
                Map.of("components", List.of(), "frameworks", List.of("Spring Boot")), List.of()));
        Map<String, Object> result = Map.of("summary", "분석 결과", "riskLevel", "UNKNOWN", "riskScore", 0);
        when(ai.analyzeWithProgress(anyMap(), any())).thenAnswer(call -> {
            assertThat(checkout).doesNotExist();
            var request = (Map<?, ?>) call.getArgument(0);
            assertThat(request.get("projectId")).isEqualTo(job.projectId().toString());
            assertThat(request.get("thinkingEnabled")).isEqualTo(true);
            assertThat(request.get("thinkingEffort")).isEqualTo("medium");
            assertThat(request.toString()).doesNotContain("repositoryUrl", "username", "password");
            @SuppressWarnings("unchecked")
            var progress = (java.util.function.Consumer<AiAnalysisClient.ProgressEvent>) call.getArgument(1);
            progress.accept(new AiAnalysisClient.ProgressEvent("GENERAL_STARTED", null));
            progress.accept(new AiAnalysisClient.ProgressEvent("GENERAL_COMPLETED", null));
            progress.accept(new AiAnalysisClient.ProgressEvent("DATABASE_SKIPPED", null));
            var response = new java.util.LinkedHashMap<>(result);
            response.put("generalAnalysis", Map.of("databaseRelevance", "UNCERTAIN",
                    "databaseRelevanceReason", "추가 DB 확인이 필요합니다."));
            return response;
        });
        worker.process(job);
        var sequence = inOrder(jobs, results);
        sequence.verify(jobs).progress(job.jobId(), 40, 0, 0);
        sequence.verify(jobs).progress(job.jobId(), 40, 100, 0);
        sequence.verify(jobs).progress(job.jobId(), 100, 100, 0);
        sequence.verify(jobs).progress(job.jobId(), 100, 100, 40);
        sequence.verify(jobs).progress(job.jobId(), 100, 100, 90);
        sequence.verify(results).complete(eq(job.jobId()), eq(result), anyMap());
        verify(results).complete(eq(job.jobId()), argThat(value -> !value.containsKey("generalAnalysis")),
                argThat(facts -> facts.get("generalAnalysis") instanceof Map<?, ?> saved
                        && "UNCERTAIN".equals(saved.get("databaseRelevance"))));
        verify(jobs).appendActivity(job.jobId(), "Git 소스코드 수집", "STARTED");
        verify(jobs).appendActivity(job.jobId(), "Git 소스코드 수집", "COMPLETED");
        verify(jobs).appendActivity(job.jobId(), "AI 모델의 분석 응답 대기", "STARTED");
        verify(jobs).appendActivity(job.jobId(), "AI 모델의 분석 응답 대기", "COMPLETED");
        verify(jobs).appendActivity(job.jobId(), "AI 일반 영향 분석", "STARTED");
        verify(jobs).appendActivity(job.jobId(), "AI 일반 영향 분석", "COMPLETED");
        verify(jobs).appendActivity(job.jobId(), "추가 DB 분석 대상 아님", "COMPLETED");
        verify(jobs).appendActivity(job.jobId(), "분석 결과 저장", "STARTED");
        verify(jobs, never()).fail(any(), anyString());
        assertThat(output.getAll()).contains("Analysis started: jobId=" + job.jobId(),
                "Analysis stage started: jobId=" + job.jobId() + ", stage=Git 커밋 소스 수집",
                "Analysis stage completed: jobId=" + job.jobId() + ", stage=Git 커밋 소스 수집",
                "Analysis stage started: jobId=" + job.jobId() + ", stage=AI 분석 요청",
                "Analysis completed: jobId=" + job.jobId());
    }

    @Test void databaseFailureStopsModelAndPersistsOnlySafeStage() {
        when(database.collect(job.projectId())).thenThrow(new IllegalStateException("password=unsafe-fixture"));
        worker.process(job);
        verify(jobs).fail(eq(job.jobId()), argThat(message -> message.contains("DB 구조 수집")
                && !message.contains("unsafe-fixture")));
        verifyNoInteractions(ai, source);
        verify(jobs, never()).appendActivity(any(), contains("unsafe-fixture"), anyString());
        verify(results, never()).complete(any(), anyMap(), anyMap());
        assertThat(checkout).doesNotExist();
    }

    @Test void activityStorageFailureDoesNotAbortAnalysisOrLeakDetails(CapturedOutput output) throws Exception {
        var db = Map.<String, Object>of("tables", List.of());
        when(database.collect(job.projectId())).thenReturn(db);
        when(source.collect(checkout, db)).thenReturn(new SourceEvidenceCollector.ScanResult(Map.of(), List.of()));
        when(ai.analyzeWithProgress(anyMap(), any())).thenReturn(Map.of("summary", "완료", "riskLevel", "UNKNOWN", "riskScore", 0));
        doThrow(new IllegalStateException("token=unsafe-fixture"))
                .when(jobs).appendActivity(any(), anyString(), anyString());

        worker.process(job);

        verify(results).complete(eq(job.jobId()), anyMap(), anyMap());
        verify(jobs, never()).fail(any(), anyString());
        assertThat(output.getAll()).contains("Analysis activity update failed: jobId=" + job.jobId())
                .doesNotContain("unsafe-fixture");
    }

    @Test void gitFailureAtZeroProgressRecordsSafeStage(CapturedOutput output) throws Exception {
        when(git.checkoutSnapshot(job.projectId(), job.requestedBy(), job.repositoryUrl(), job.branch(), job.commitSha()))
                .thenThrow(new java.io.IOException("token=unsafe-fixture"));

        worker.process(job);

        verify(jobs, never()).progress(any(), anyInt(), anyInt(), anyInt());
        verify(jobs).fail(eq(job.jobId()), argThat(message -> message.contains("Git 커밋 소스 수집")
                && message.contains("오류 코드: ANALYSIS_STAGE_FAILURE")
                && message.contains("처리 인스턴스:")
                && !message.contains("unsafe-fixture")));
        verifyNoInteractions(database, source, ai);
        verify(results, never()).complete(any(), anyMap(), anyMap());
        assertThat(output.getAll()).contains("Analysis stage started: jobId=" + job.jobId()
                        + ", stage=Git 커밋 소스 수집",
                "Analysis failed: jobId=" + job.jobId() + ", stage=Git 커밋 소스 수집")
                .doesNotContain("unsafe-fixture");
    }

    @Test void cleanupFailureRecordsSafeCodeAreaAndWorkerInstance(CapturedOutput output) throws Exception {
        var snapshot = mock(GitRepositoryConnectionService.GitSnapshot.class);
        when(snapshot.path()).thenReturn(checkout);
        doThrow(GitRepositoryConnectionService.SnapshotCleanupException.from(checkout,
                new DirectoryNotEmptyException(checkout.resolve("source/private-token.sql").toString())))
                .when(snapshot).close();
        when(git.checkoutSnapshot(job.projectId(), job.requestedBy(), job.repositoryUrl(), job.branch(), job.commitSha()))
                .thenReturn(snapshot);
        var db = Map.<String, Object>of("tables", List.of());
        when(database.collect(job.projectId())).thenReturn(db);
        when(source.collect(checkout, db)).thenReturn(new SourceEvidenceCollector.ScanResult(Map.of(), List.of()));

        worker.process(job);

        verify(jobs).fail(eq(job.jobId()), argThat(message -> message.contains("GIT_CLEANUP_DIRECTORY_NOT_EMPTY")
                && message.contains("정리 영역: SOURCE")
                && message.contains("처리 인스턴스:")
                && !message.contains("private-token.sql")));
        verifyNoInteractions(ai);
        assertThat(output.getAll()).contains("stage=Git 임시 파일 정리", "code=GIT_CLEANUP_DIRECTORY_NOT_EMPTY", "area=SOURCE")
                .doesNotContain("private-token.sql");
    }

    @Test void revokedProjectAccessStopsBeforeCredentialUse() {
        doThrow(new IllegalStateException("access denied")).when(projects)
                .assertCanManage(job.projectId(), job.requestedBy());
        worker.process(job);
        verifyNoInteractions(git, database, source, ai, results);
        verify(jobs).fail(eq(job.jobId()), contains("접근 권한"));
    }

    @Test void failureStatusWriteErrorStillLogsJobAndStageWithoutSecrets(CapturedOutput output) {
        doThrow(new IllegalStateException("password=unsafe-fixture")).when(projects)
                .assertCanManage(job.projectId(), job.requestedBy());
        doThrow(new IllegalStateException("token=unsafe-fixture")).when(jobs)
                .fail(eq(job.jobId()), anyString());

        assertThatCode(() -> worker.process(job)).doesNotThrowAnyException();

        assertThat(output.getAll()).contains("Analysis failed: jobId=" + job.jobId(),
                        "stage=프로젝트 접근 권한 확인, exception=IllegalStateException",
                        "Analysis failure status update failed: jobId=" + job.jobId())
                .doesNotContain("unsafe-fixture");
        verifyNoInteractions(git, database, source, ai, results);
    }

    @Test void modelFailureDoesNotCreateSuccessResult() throws Exception {
        var db = Map.<String, Object>of("tables", List.of());
        when(database.collect(job.projectId())).thenReturn(db);
        when(source.collect(checkout, db)).thenReturn(new SourceEvidenceCollector.ScanResult(Map.of(), List.of()));
        when(ai.analyzeWithProgress(any(), any())).thenThrow(new java.io.IOException("HTTP 504"));
        worker.process(job);
        verify(jobs).fail(eq(job.jobId()), contains("AI 분석 요청"));
        verify(results, never()).complete(any(), anyMap(), anyMap());
        assertThat(checkout).doesNotExist();
    }

    @Test void modelTimeoutKeepsItsCodeAndSafeReasonInsteadOfGenericStageFailure(CapturedOutput output) throws Exception {
        var db = Map.<String, Object>of("tables", List.of());
        when(database.collect(job.projectId())).thenReturn(db);
        when(source.collect(checkout, db)).thenReturn(new SourceEvidenceCollector.ScanResult(Map.of(), List.of()));
        when(ai.analyzeWithProgress(any(), any())).thenThrow(new AiAnalysisClient.ModelFailureException("MODEL_TIMEOUT"));
        worker.process(job);
        verify(jobs).fail(eq(job.jobId()), argThat(message -> message.contains("MODEL_TIMEOUT")
                && message.contains("응답 대기 시간이 초과") && !message.contains("ANALYSIS_STAGE_FAILURE")));
        verify(results, never()).complete(any(), anyMap(), anyMap());
        assertThat(output.getAll()).contains("code=MODEL_TIMEOUT");
    }

    @Test void persistsRealBatchAndReusedStageMessagesForTheProgressScreen() throws Exception {
        var db = Map.<String, Object>of("tables", List.of());
        when(database.collect(job.projectId())).thenReturn(db);
        when(source.collect(checkout, db)).thenReturn(new SourceEvidenceCollector.ScanResult(Map.of(), List.of()));
        when(ai.analyzeWithProgress(anyMap(), any())).thenAnswer(call -> {
            @SuppressWarnings("unchecked")
            var report = (java.util.function.Consumer<AiAnalysisClient.ProgressEvent>) call.getArgument(1);
            report.accept(new AiAnalysisClient.ProgressEvent("GENERAL_REUSED", null));
            report.accept(new AiAnalysisClient.ProgressEvent("DATABASE_BATCH_STARTED", 2));
            report.accept(new AiAnalysisClient.ProgressEvent("DATABASE_BATCH_REUSED", 2));
            report.accept(new AiAnalysisClient.ProgressEvent("DATABASE_MERGE_COMPLETED", 1));
            report.accept(new AiAnalysisClient.ProgressEvent("RECONCILIATION_STARTED", null));
            report.accept(new AiAnalysisClient.ProgressEvent("RECONCILIATION_REUSED", null));
            report.accept(new AiAnalysisClient.ProgressEvent("RECONCILIATION_COMPLETED", null));
            report.accept(new AiAnalysisClient.ProgressEvent("DATABASE_SCOPE_FOCUSED", 2));
            report.accept(new AiAnalysisClient.ProgressEvent("DATABASE_SCOPE_FULL", 26));
            return Map.of("summary", "검토 완료", "riskLevel", "UNKNOWN", "riskScore", 0);
        });
        worker.process(job);
        verify(jobs).appendActivity(job.jobId(), "저장된 AI 일반 분석 불러오기", "COMPLETED");
        verify(jobs).appendActivity(job.jobId(), "AI DB 분할 검토 (2번째 묶음)", "STARTED");
        verify(jobs).appendActivity(job.jobId(), "AI DB 분할 검토 (2번째 묶음) · 저장 결과 재사용", "COMPLETED");
        verify(jobs).appendActivity(job.jobId(), "AI DB 분할 결과 종합 (1번째 묶음)", "COMPLETED");
        verify(jobs).appendActivity(job.jobId(), "일반·DB 분석 결과 최종 정리", "STARTED");
        verify(jobs).appendActivity(job.jobId(), "일반·DB 분석 결과 최종 정리 · 저장 결과 재사용", "COMPLETED");
        verify(jobs).appendActivity(job.jobId(), "일반·DB 분석 결과 최종 정리", "COMPLETED");
        verify(jobs).appendActivity(job.jobId(), "요구사항 관련 DB 범위 우선 검토 (2개 테이블)", "COMPLETED");
        verify(jobs).appendActivity(job.jobId(), "수집한 전체 DB 범위 검토 (26개 테이블)", "COMPLETED");
    }

    @Test void includesValidatedRequirementVersionButNotRawOriginalOrAttachments() throws Exception {
        var versionId = UUID.randomUUID();
        var previousJobId = UUID.randomUUID();
        var targeted = new AnalysisWorkItem(job.jobId(), job.projectId(), versionId, job.requestedBy(),
                job.repositoryUrl(), job.branch(), job.commitSha());
        var db = Map.<String, Object>of("tables", List.of());
        when(database.collect(job.projectId())).thenReturn(db);
        when(source.collect(checkout, db)).thenReturn(new SourceEvidenceCollector.ScanResult(Map.of(), List.of()));
        when(versions.findById(versionId)).thenReturn(java.util.Optional.of(
                new com.doenaai.requirement.entity.RequirementVersion(versionId, UUID.randomUUID(), 2,
                        "이메일 컬럼 변경", "original-must-not-be-sent", "users.email 이름 변경 영향 분석",
                        List.of("기존 조회 기능 유지"), List.of("attachment-must-not-be-sent"), null,
                        List.of(Map.of("kind", "AI_QUESTION_ANSWER", "sourceAnalysisJobId", previousJobId.toString(),
                                        "question", "어느 테이블인가요?", "answer", "organization_members입니다."),
                                Map.of("kind", "USER_QUESTION", "sourceAnalysisJobId", previousJobId.toString(),
                                        "question", "다른 조직의 데이터에는 어떤 영향이 있나요?")), previousJobId, null,
                        true, job.requestedBy(), java.time.OffsetDateTime.now())));
        when(ai.analyzeWithProgress(anyMap(), any())).thenAnswer(call -> {
            String facts = call.getArgument(0).toString();
            assertThat(facts).contains("REQUIREMENT_IMPACT", "users.email", versionId.toString(),
                            "organization_members입니다.", "다른 조직의 데이터에는 어떤 영향이 있나요?", "latestFollowUpQuestion")
                    .doesNotContain("original-must-not-be-sent", "attachment-must-not-be-sent");
            return Map.of("summary", "영향 분석", "riskLevel", "UNKNOWN", "riskScore", 0);
        });
        worker.process(targeted);
        verify(requirements).assertVersionBelongsToProject(versionId, job.projectId(), job.requestedBy());
        verify(results).complete(eq(job.jobId()), anyMap(), anyMap());
    }

    @Test void keepsLongFollowUpHistoryStoredButBoundsModelContext() throws Exception {
        var versionId = UUID.randomUUID();
        var targeted = new AnalysisWorkItem(job.jobId(), job.projectId(), versionId, job.requestedBy(),
                job.repositoryUrl(), job.branch(), job.commitSha());
        var db = Map.<String, Object>of("tables", List.of());
        when(database.collect(job.projectId())).thenReturn(db);
        when(source.collect(checkout, db)).thenReturn(new SourceEvidenceCollector.ScanResult(Map.of(), List.of()));
        List<Object> history = new ArrayList<>();
        for (int index = 0; index < 60; index++) {
            history.add(Map.of("kind", "USER_QUESTION", "question", "질문 " + index + " " + "가".repeat(300)));
        }
        when(versions.findById(versionId)).thenReturn(java.util.Optional.of(
                new com.doenaai.requirement.entity.RequirementVersion(versionId, UUID.randomUUID(), 61,
                        "변경", "원문", "영향 분석", List.of(), List.of(), null, history, null, null,
                        true, job.requestedBy(), java.time.OffsetDateTime.now())));
        when(ai.analyzeWithProgress(anyMap(), any())).thenAnswer(call -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = call.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> sourceSummary = (Map<String, Object>) payload.get("sourceSummary");
            @SuppressWarnings("unchecked")
            Map<String, Object> requirement = (Map<String, Object>) sourceSummary.get("changeRequirement");
            @SuppressWarnings("unchecked")
            List<Object> included = (List<Object>) requirement.get("clarifications");
            @SuppressWarnings("unchecked")
            Map<String, Object> coverage = (Map<String, Object>) requirement.get("clarificationCoverage");
            assertThat(included).hasSizeLessThan(history.size());
            assertThat(included.getLast()).isEqualTo(history.getLast());
            assertThat(coverage).containsEntry("total", 60)
                    .containsEntry("included", included.size())
                    .containsEntry("omitted", 60 - included.size());
            return Map.of("summary", "완료", "riskLevel", "UNKNOWN", "riskScore", 0);
        });

        worker.process(targeted);

        verify(results).complete(eq(job.jobId()), anyMap(), anyMap());
    }

    @Test void reusesCompleteEvidenceWithSameCommitAndSchemaButReplacesRequirement() throws Exception {
        UUID oldJobId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        var targeted = new AnalysisWorkItem(job.jobId(), job.projectId(), versionId, job.requestedBy(),
                job.repositoryUrl(), job.branch(), job.commitSha());
        Map<String, Object> db = Map.of("type", "POSTGRESQL", "schema", "public", "tables", List.of(),
                "warnings", List.of(), "truncated", false);
        Map<String, Object> oldSource = Map.of("commitSha", job.commitSha(), "components", List.of(),
                "collectorVersion", SourceEvidenceVersion.CURRENT,
                "promptCoverage", Map.of("truncated", false),
                "scanMetrics", Map.of("truncated", false),
                "changeRequirement", Map.of("title", "이전 요구사항"));
        when(results.findReusableEvidence(job.projectId(), job.commitSha())).thenReturn(java.util.Optional.of(
                new ReusableEvidence(oldJobId, oldSource, db, List.of())));
        when(database.collect(job.projectId())).thenReturn(db);
        when(versions.findById(versionId)).thenReturn(java.util.Optional.of(
                new com.doenaai.requirement.entity.RequirementVersion(versionId, UUID.randomUUID(), 2,
                        "새 요구사항", "원문", "새 영향 분석", List.of(), List.of(), null, List.of(), null, null,
                        true, job.requestedBy(), java.time.OffsetDateTime.now())));
        when(ai.analyzeWithProgress(anyMap(), any())).thenAnswer(call -> {
            String input = call.getArgument(0).toString();
            assertThat(input).contains("새 요구사항", "새 영향 분석").doesNotContain("이전 요구사항", "원문");
            return Map.of("summary", "재사용된 근거 분석", "riskLevel", "UNKNOWN", "riskScore", 0);
        });

        worker.process(targeted);

        verify(git, never()).checkoutSnapshot(any(), any(), any(), any(), any());
        verifyNoInteractions(source);
        verify(jobs).markEvidenceReused(job.jobId(), oldJobId);
        verify(results).complete(eq(job.jobId()), anyMap(), anyMap());
    }

    @Test void changedDatabaseFallsBackToSourceCollection() throws Exception {
        UUID oldJobId = UUID.randomUUID();
        Map<String, Object> oldDb = Map.of("type", "POSTGRESQL", "schema", "public", "tables", List.of(),
                "warnings", List.of(), "truncated", false);
        Map<String, Object> currentDb = Map.of("type", "POSTGRESQL", "schema", "changed", "tables", List.of(),
                "warnings", List.of(), "truncated", false);
        when(results.findReusableEvidence(job.projectId(), job.commitSha())).thenReturn(java.util.Optional.of(
                new ReusableEvidence(oldJobId, Map.of("commitSha", job.commitSha(),
                        "collectorVersion", SourceEvidenceVersion.CURRENT,
                        "promptCoverage", Map.of("truncated", false),
                        "scanMetrics", Map.of("truncated", false)), oldDb, List.of())));
        when(database.collect(job.projectId())).thenReturn(currentDb);
        when(source.collect(checkout, currentDb)).thenReturn(new SourceEvidenceCollector.ScanResult(Map.of(), List.of()));
        when(ai.analyzeWithProgress(anyMap(), any())).thenReturn(Map.of("summary", "새 수집", "riskLevel", "UNKNOWN", "riskScore", 0));

        worker.process(job);

        verify(git).checkoutSnapshot(any(), any(), any(), any(), any());
        verify(database, times(1)).collect(job.projectId());
        verify(jobs, never()).markEvidenceReused(any(), any());
        verify(results).complete(eq(job.jobId()), anyMap(), anyMap());
    }

    @Test void explicitlySavedEvidenceSkipsGitAndDatabaseButRunsNewModelAnalysis() throws Exception {
        UUID sourceJobId = UUID.randomUUID();
        var savedJob = new AnalysisWorkItem(job.jobId(), job.projectId(), null, job.requestedBy(),
                job.repositoryUrl(), job.branch(), job.commitSha(), sourceJobId, false, "high");
        var db = Map.<String, Object>of("type", "POSTGRESQL", "tables", List.of(),
                "warnings", List.of(), "truncated", false);
        var sourceFacts = Map.<String, Object>of("commitSha", job.commitSha(), "components", List.of(),
                "collectorVersion", SourceEvidenceVersion.CURRENT,
                "promptCoverage", Map.of("truncated", false), "scanMetrics", Map.of("truncated", false));
        when(results.findReusableEvidenceByJobId(job.projectId(), sourceJobId)).thenReturn(java.util.Optional.of(
                new ReusableEvidence(sourceJobId, sourceFacts, db, List.of())));
        when(ai.analyzeWithProgress(anyMap(), any())).thenReturn(Map.of("summary", "새 질문의 답", "riskLevel", "UNKNOWN",
                "riskScore", 0, "analysisThinkingEnabled", false));

        worker.process(savedJob);

        verifyNoInteractions(git, database, source);
        verify(ai).analyzeWithProgress(argThat(payload -> Boolean.FALSE.equals(payload.get("thinkingEnabled"))
                && "high".equals(payload.get("thinkingEffort"))), any());
        verify(results).complete(eq(job.jobId()), argThat(result -> !result.containsKey("analysisThinkingEnabled")),
                argThat(facts -> Boolean.FALSE.equals(facts.get("thinkingEnabled"))
                        && Boolean.FALSE.equals(facts.get("analysisThinkingEnabled"))
                        && "high".equals(facts.get("thinkingEffort")) && !facts.containsKey("analysisThinkingEffort")));
    }

    @Test void legacyUnknownThinkingSettingReachesModelAsNullAndStaysUnknownInFacts() throws Exception {
        var legacyJob = new AnalysisWorkItem(job.jobId(), job.projectId(), null, job.requestedBy(),
                job.repositoryUrl(), job.branch(), job.commitSha(), null, null);
        var db = Map.<String, Object>of("tables", List.of());
        when(database.collect(job.projectId())).thenReturn(db);
        when(source.collect(checkout, db)).thenReturn(new SourceEvidenceCollector.ScanResult(Map.of(), List.of()));
        when(ai.analyzeWithProgress(anyMap(), any())).thenReturn(Map.of("summary", "완료", "riskLevel", "UNKNOWN", "riskScore", 0));

        worker.process(legacyJob);

        verify(ai).analyzeWithProgress(argThat(payload -> payload.containsKey("thinkingEnabled")
                && payload.get("thinkingEnabled") == null && payload.containsKey("thinkingEffort")
                && payload.get("thinkingEffort") == null), any());
        verify(results).complete(eq(job.jobId()), anyMap(), argThat(facts -> facts.containsKey("thinkingEnabled")
                && facts.get("thinkingEnabled") == null && facts.containsKey("thinkingEffort")
                && facts.get("thinkingEffort") == null));
        verify(jobs, never()).fail(any(), anyString());
    }

    @Test void requestedAndReportedThinkingEffortAreStoredAsFactsOutsideThePublicResult() throws Exception {
        var selectedJob = new AnalysisWorkItem(job.jobId(), job.projectId(), null, job.requestedBy(),
                job.repositoryUrl(), job.branch(), job.commitSha(), null, true, "high");
        var db = Map.<String, Object>of("tables", List.of());
        when(database.collect(job.projectId())).thenReturn(db);
        when(source.collect(checkout, db)).thenReturn(new SourceEvidenceCollector.ScanResult(Map.of(), List.of()));
        when(ai.analyzeWithProgress(anyMap(), any())).thenReturn(Map.of("summary", "완료", "riskLevel", "UNKNOWN",
                "riskScore", 0, "analysisThinkingEnabled", true, "analysisThinkingEffort", "high"));

        worker.process(selectedJob);

        verify(ai).analyzeWithProgress(argThat(payload -> "high".equals(payload.get("thinkingEffort"))), any());
        verify(results).complete(eq(job.jobId()), argThat(result -> !result.containsKey("analysisThinkingEffort")),
                argThat(facts -> "high".equals(facts.get("thinkingEffort"))
                        && "high".equals(facts.get("analysisThinkingEffort"))));
        verify(jobs, never()).fail(any(), anyString());
    }

    @Test void conflictingServerThinkingEffortRejectsTheResultInsteadOfMisreportingTheSelectedEffort() throws Exception {
        var db = Map.<String, Object>of("tables", List.of());
        when(database.collect(job.projectId())).thenReturn(db);
        when(source.collect(checkout, db)).thenReturn(new SourceEvidenceCollector.ScanResult(Map.of(), List.of()));
        when(ai.analyzeWithProgress(anyMap(), any())).thenReturn(Map.of("summary", "완료", "riskLevel", "UNKNOWN",
                "riskScore", 0, "analysisThinkingEnabled", true, "analysisThinkingEffort", "high"));

        worker.process(job);

        verify(results, never()).complete(any(), anyMap(), anyMap());
        verify(jobs).fail(eq(job.jobId()), argThat(message -> message.contains("THINKING_EFFORT_MISMATCH")
                && message.contains("분석 결과를 저장하지 않았습니다")));
    }

    @Test void invalidServerThinkingEffortRejectsTheResult() throws Exception {
        var db = Map.<String, Object>of("tables", List.of());
        when(database.collect(job.projectId())).thenReturn(db);
        when(source.collect(checkout, db)).thenReturn(new SourceEvidenceCollector.ScanResult(Map.of(), List.of()));
        when(ai.analyzeWithProgress(anyMap(), any())).thenReturn(Map.of("summary", "완료", "riskLevel", "UNKNOWN",
                "riskScore", 0, "analysisThinkingEffort", 42));

        worker.process(job);

        verify(results, never()).complete(any(), anyMap(), anyMap());
        verify(jobs).fail(eq(job.jobId()), contains("THINKING_EFFORT_MISMATCH"));
    }

    @Test void conflictingServerThinkingModeRejectsTheResultInsteadOfRecordingTheRequestedModeAsExecuted() throws Exception {
        var db = Map.<String, Object>of("tables", List.of());
        when(database.collect(job.projectId())).thenReturn(db);
        when(source.collect(checkout, db)).thenReturn(new SourceEvidenceCollector.ScanResult(Map.of(), List.of()));
        when(ai.analyzeWithProgress(anyMap(), any())).thenReturn(Map.of("summary", "완료", "riskLevel", "UNKNOWN",
                "riskScore", 0, "analysisThinkingEnabled", false));

        worker.process(job);

        verify(results, never()).complete(any(), anyMap(), anyMap());
        verify(jobs).fail(eq(job.jobId()), argThat(message -> message.contains("THINKING_MODE_MISMATCH")
                && message.contains("분석 결과를 저장하지 않았습니다")));
    }

    @Test void oldCollectorFactsAreRecollectedEvenWhenCommitAndDatabaseAreUnchanged() throws Exception {
        var db = Map.<String, Object>of("type", "POSTGRESQL", "tables", List.of(), "warnings", List.of(), "truncated", false);
        var oldSource = Map.<String, Object>of("commitSha", job.commitSha(), "promptCoverage", Map.of("truncated", false),
                "scanMetrics", Map.of("truncated", false));
        var fresh = Map.<String, Object>of("collectorVersion", SourceEvidenceVersion.CURRENT, "sourceConnections", Map.of("version", 1));
        when(results.findReusableEvidence(job.projectId(), job.commitSha())).thenReturn(java.util.Optional.of(
                new ReusableEvidence(UUID.randomUUID(), oldSource, db, List.of())));
        when(database.collect(job.projectId())).thenReturn(db);
        when(source.collect(checkout, db)).thenReturn(new SourceEvidenceCollector.ScanResult(fresh, List.of()));
        when(ai.analyzeWithProgress(anyMap(), any())).thenAnswer(call -> {
            assertThat(call.getArgument(0).toString()).contains("collectorVersion=" + SourceEvidenceVersion.CURRENT, "sourceConnections");
            return Map.of("summary", "새로 수집한 근거 분석", "riskLevel", "UNKNOWN", "riskScore", 0);
        });

        worker.process(job);

        verify(git).checkoutSnapshot(any(), any(), any(), any(), any());
        verify(source).collect(checkout, db);
        verify(database, times(1)).collect(job.projectId());
        verify(jobs, never()).markEvidenceReused(any(), any());
        verify(results).complete(eq(job.jobId()), anyMap(), anyMap());
    }

    @Test void queuedSavedAnalysisRejectsMissingCoreFactsBeforeRemoteCalls() {
        var sourceJobId = UUID.randomUUID();
        var savedJob = new AnalysisWorkItem(job.jobId(), job.projectId(), null, job.requestedBy(),
                job.repositoryUrl(), job.branch(), job.commitSha(), sourceJobId);
        when(results.findReusableEvidenceByJobId(job.projectId(), sourceJobId)).thenReturn(java.util.Optional.of(
                new ReusableEvidence(sourceJobId, Map.of("commitSha", job.commitSha(), "promptCoverage", Map.of("truncated", false),
                        "scanMetrics", Map.of("truncated", false)), Map.of("truncated", false, "warnings", List.of()), List.of())));

        worker.process(savedJob);

        verifyNoInteractions(git, database, source, ai);
        verify(jobs).fail(eq(job.jobId()), contains("선택한 저장 근거 확인"));
        verify(results, never()).complete(any(), anyMap(), anyMap());
    }

    @Test void explicitlySelectedPartialLegacyFactsKeepTheirLimitsWithoutCollectingCurrentState() throws Exception {
        UUID sourceJobId = UUID.randomUUID();
        var savedJob = new AnalysisWorkItem(job.jobId(), job.projectId(), null, job.requestedBy(),
                job.repositoryUrl(), job.branch(), job.commitSha(), sourceJobId, false, "high", "ALWAYS");
        var sourceFacts = Map.<String, Object>of("commitSha", job.commitSha(), "components", List.of(Map.of("path", "src/stored.py")),
                "collectorVersion", "source-evidence-v1", "promptCoverage", Map.of("collectedComponents", 30, "truncated", true),
                "scanMetrics", Map.of("truncated", true));
        var databaseFacts = Map.<String, Object>of("tables", List.of(), "truncated", true,
                "warnings", List.of("일부 구조만 수집한 자료입니다."));
        when(results.findReusableEvidenceByJobId(job.projectId(), sourceJobId)).thenReturn(java.util.Optional.of(
                new ReusableEvidence(sourceJobId, sourceFacts, databaseFacts, List.of())));
        when(ai.analyzeWithProgress(anyMap(), any())).thenReturn(Map.of("summary", "저장 시점의 부분 근거로 분석했습니다.",
                "riskLevel", "UNKNOWN", "riskScore", 0));

        worker.process(savedJob);

        verifyNoInteractions(git, database, source);
        verify(ai).analyzeWithProgress(argThat(payload -> {
            var sentSource = (Map<?, ?>) payload.get("sourceSummary");
            var coverage = (Map<?, ?>) sentSource.get("promptCoverage");
            var saved = (Map<?, ?>) sentSource.get("savedEvidence");
            return Integer.valueOf(30).equals(coverage.get("collectedComponents"))
                    && Boolean.TRUE.equals(coverage.get("truncated"))
                    && Boolean.FALSE.equals(saved.get("currentStateVerified"))
                    && ((List<?>) saved.get("warnings")).containsAll(List.of("SOURCE_COLLECTOR_OUTDATED", "SOURCE_PROMPT_TRUNCATED",
                    "SOURCE_SCAN_TRUNCATED", "DATABASE_TRUNCATED", "DATABASE_WARNINGS_PRESENT"))
                    && "source-evidence-v1".equals(sentSource.get("collectorVersion"));
        }), any());
        verify(results).complete(eq(job.jobId()), anyMap(), anyMap());
        verify(jobs, never()).fail(any(), anyString());
        assertThat(sourceFacts).doesNotContainKey("savedEvidence");
        assertThat(((Map<?, ?>) sourceFacts.get("promptCoverage")).get("truncated")).isEqualTo(true);
    }

    @Test void alreadyCancelledJobNeverStartsRemoteWork() {
        when(jobs.isCancelled(job.jobId())).thenReturn(true);

        worker.process(job);

        verifyNoInteractions(git, database, source, ai, results);
        verify(jobs, never()).fail(any(), anyString());
    }
}
