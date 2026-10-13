package com.doenaai.analysis.service;

import com.doenaai.analysis.dto.AnalysisWorkItem;
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
import java.nio.file.Path;
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
    final AnalysisWorkItem job = new AnalysisWorkItem(UUID.randomUUID(), UUID.randomUUID(), null,
            UUID.randomUUID(), "https://github.com/example/repository.git", "main", "a".repeat(40));
    AnalysisWorker worker;
    Path checkout;

    @BeforeEach void setup() throws Exception {
        worker = new AnalysisWorker(jobs, results, projects, git, database, source, requirements, versions,
                new AnalysisPromptBuilder(JsonMapper.builder().build(), AnalysisPromptBuilder.DEFAULT_MAX_CHARS), ai);
        checkout = Files.createDirectory(directory.resolve("checkout"));
        when(git.checkoutSnapshot(job.projectId(), job.requestedBy(), job.repositoryUrl(), job.branch(), job.commitSha()))
                .thenReturn(new GitRepositoryConnectionService.GitSnapshot(checkout, checkout));
    }

    @Test void collectsRealFactsCompletesAndDeletesCheckoutBeforeModelCall(CapturedOutput output) throws Exception {
        var db = Map.<String, Object>of("type", "POSTGRESQL", "tables", List.of());
        when(database.collect(job.projectId())).thenReturn(db);
        when(source.collect(checkout, db)).thenReturn(new SourceEvidenceCollector.ScanResult(
                Map.of("components", List.of(), "frameworks", List.of("Spring Boot")), List.of()));
        Map<String, Object> result = Map.of("summary", "분석 결과", "riskLevel", "UNKNOWN", "riskScore", 0);
        when(ai.analyze(anyMap())).thenAnswer(call -> {
            assertThat(checkout).doesNotExist();
            var request = (Map<?, ?>) call.getArgument(0);
            assertThat(request.get("projectId")).isEqualTo(job.projectId().toString());
            assertThat(request.toString()).doesNotContain("repositoryUrl", "username", "password");
            return result;
        });
        worker.process(job);
        var sequence = inOrder(jobs, results);
        sequence.verify(jobs).progress(job.jobId(), 40, 0, 0);
        sequence.verify(jobs).progress(job.jobId(), 40, 100, 0);
        sequence.verify(jobs).progress(job.jobId(), 100, 100, 40);
        sequence.verify(jobs).progress(job.jobId(), 100, 100, 90);
        sequence.verify(results).complete(eq(job.jobId()), eq(result), anyMap());
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
        verifyNoInteractions(ai, source, results);
        assertThat(checkout).doesNotExist();
    }

    @Test void gitFailureAtZeroProgressRecordsSafeStage(CapturedOutput output) throws Exception {
        when(git.checkoutSnapshot(job.projectId(), job.requestedBy(), job.repositoryUrl(), job.branch(), job.commitSha()))
                .thenThrow(new java.io.IOException("token=unsafe-fixture"));

        worker.process(job);

        verify(jobs, never()).progress(any(), anyInt(), anyInt(), anyInt());
        verify(jobs).fail(eq(job.jobId()), argThat(message -> message.contains("Git 커밋 소스 수집")
                && !message.contains("unsafe-fixture")));
        verifyNoInteractions(database, source, ai, results);
        assertThat(output.getAll()).contains("Analysis stage started: jobId=" + job.jobId()
                        + ", stage=Git 커밋 소스 수집",
                "Analysis failed: jobId=" + job.jobId() + ", stage=Git 커밋 소스 수집")
                .doesNotContain("unsafe-fixture");
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
        when(ai.analyze(any())).thenThrow(new java.io.IOException("HTTP 504"));
        worker.process(job);
        verify(jobs).fail(eq(job.jobId()), contains("AI 분석 요청"));
        verifyNoInteractions(results);
        assertThat(checkout).doesNotExist();
    }

    @Test void includesValidatedRequirementVersionButNotRawOriginalOrAttachments() throws Exception {
        var versionId = UUID.randomUUID();
        var targeted = new AnalysisWorkItem(job.jobId(), job.projectId(), versionId, job.requestedBy(),
                job.repositoryUrl(), job.branch(), job.commitSha());
        var db = Map.<String, Object>of("tables", List.of());
        when(database.collect(job.projectId())).thenReturn(db);
        when(source.collect(checkout, db)).thenReturn(new SourceEvidenceCollector.ScanResult(Map.of(), List.of()));
        when(versions.findById(versionId)).thenReturn(java.util.Optional.of(
                new com.doenaai.requirement.entity.RequirementVersion(versionId, UUID.randomUUID(), 2,
                        "이메일 컬럼 변경", "original-must-not-be-sent", "users.email 이름 변경 영향 분석",
                        List.of("기존 조회 기능 유지"), List.of("attachment-must-not-be-sent"), null,
                        true, job.requestedBy(), java.time.OffsetDateTime.now())));
        when(ai.analyze(anyMap())).thenAnswer(call -> {
            String facts = call.getArgument(0).toString();
            assertThat(facts).contains("REQUIREMENT_IMPACT", "users.email", versionId.toString())
                    .doesNotContain("original-must-not-be-sent", "attachment-must-not-be-sent");
            return Map.of("summary", "영향 분석", "riskLevel", "UNKNOWN", "riskScore", 0);
        });
        worker.process(targeted);
        verify(requirements).assertVersionBelongsToProject(versionId, job.projectId(), job.requestedBy());
        verify(results).complete(eq(job.jobId()), anyMap(), anyMap());
    }
}
