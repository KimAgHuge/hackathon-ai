package com.doenaai.analysis.service;

import com.doenaai.analysis.dto.TrainingCaseDraftResponse;
import com.doenaai.analysis.repository.AnalysisJobRepository;
import com.doenaai.project.connection.service.GitRepositoryConnectionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrainingEvidenceServiceTest {
    @TempDir Path directory;
    final UUID jobId = UUID.randomUUID(), userId = UUID.randomUUID(), projectId = UUID.randomUUID();
    final String revision = "a".repeat(40);
    final AnalysisResultService results = mock(AnalysisResultService.class);
    final AnalysisJobRepository jobs = mock(AnalysisJobRepository.class);
    final GitRepositoryConnectionService git = mock(GitRepositoryConnectionService.class);
    final TrainingEvidenceService service = new TrainingEvidenceService(results, jobs, git);

    Map<String, Object> sample(int line) {
        return Map.of("caseId", "analysis-" + jobId, "humanApproved", true, "sanitized", true,
                "request", Map.of("projectId", projectId.toString(), "sourceSummary", Map.of("commitSha", revision,
                        "components", List.of(Map.of("path", "schema.sql", "line", 1)))),
                "snapshot", Map.of("requirementVersionId", "version-1", "sourceRevision", revision),
                "answers", Map.of("general", Map.of("evidences", List.of(Map.of("file", "schema.sql", "line", line)))));
    }

    void approved() {
        when(results.trainingDraft(jobId, userId)).thenReturn(new TrainingCaseDraftResponse(sample(1), List.of()));
        when(jobs.findTrainingSource(jobId)).thenReturn(Optional.of(new AnalysisJobRepository.TrainingSource(
                "https://example.com/repository.git", "main", revision)));
    }

    @Test void addsVerifiedExcerptOnlyToCopyAndResetsReviewFlags() throws Exception {
        approved();
        var root = Files.createDirectory(directory.resolve("checkout"));
        Files.writeString(root.resolve("schema.sql"), "-- 구조\n\nalter table example enable row level security;\n");
        when(git.checkoutSnapshot(projectId, userId, "https://example.com/repository.git", "main", revision))
                .thenReturn(new GitRepositoryConnectionService.GitSnapshot(root, root));
        var submitted = sample(3);
        var answer = service.supplement(jobId, userId, submitted);
        var request = (Map<?, ?>) answer.caseDraft().get("request");
        var source = (Map<?, ?>) request.get("sourceSummary");
        var evidence = (Map<?, ?>) ((List<?>) source.get("reviewedEvidence")).getFirst();
        assertThat(evidence.get("file")).isEqualTo("schema.sql");
        assertThat(evidence.get("line")).isEqualTo(3);
        assertThat(evidence.get("excerpt").toString()).contains("alter table example");
        assertThat(evidence.get("sourceRevision")).isEqualTo(revision);
        assertThat(answer.caseDraft().get("humanApproved")).isEqualTo(false);
        assertThat(((Map<?, ?>) ((Map<?, ?>) submitted.get("request")).get("sourceSummary")).containsKey("reviewedEvidence"))
                .isFalse();
        assertThat(root).doesNotExist();
    }

    @Test void rechecksForgedSupplementAtPublishInsteadOfTrustingBrowser() throws Exception {
        approved();
        var root = Files.createDirectory(directory.resolve("checkout"));
        Files.writeString(root.resolve("schema.sql"), "-- 구조\n-- 확인\nselect 1;\n");
        when(git.checkoutSnapshot(any(), any(), any(), any(), any()))
                .thenReturn(new GitRepositoryConnectionService.GitSnapshot(root, root));
        var submitted = new LinkedHashMap<>(sample(3));
        var source = new LinkedHashMap<String, Object>(Map.of("commitSha", revision, "reviewedEvidence",
                List.of(Map.of("file", "schema.sql", "line", 3, "excerpt", "조작한 근거"))));
        submitted.put("request", Map.of("projectId", projectId.toString(), "sourceSummary", source));
        var checked = service.verifiedCase(jobId, userId, submitted);
        var checkedSource = (Map<?, ?>) ((Map<?, ?>) checked.get("request")).get("sourceSummary");
        assertThat(checkedSource.toString()).contains("select 1;").doesNotContain("조작한 근거");
        assertThat(checked.get("humanApproved")).isEqualTo(true);
    }

    @Test void avoidsGitForAlreadyKnownReferencesAndRejectsAnotherCase() {
        approved();
        service.verifiedCase(jobId, userId, sample(1));
        verifyNoInteractions(git);
        var foreign = new LinkedHashMap<>(sample(3));
        foreign.put("caseId", "analysis-" + UUID.randomUUID());
        assertThatThrownBy(() -> service.verifiedCase(jobId, userId, foreign)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(git);
    }

    @Test void rejectsEscapingSecretFilesAndWrongLines() throws Exception {
        Files.writeString(directory.resolve("schema.sql"), "select 1;");
        Files.writeString(directory.resolve("private.key"), "임의 값");
        for (String path : List.of("../schema.sql", "/schema.sql", ".env", "private.key", "C:\\file.sql")) {
            assertThatThrownBy(() -> TrainingEvidenceService.readEvidence(directory,
                    new TrainingEvidenceService.Reference(path, 1), revision)).isInstanceOf(Exception.class);
        }
        for (int line : List.of(0, 2)) assertThatThrownBy(() -> TrainingEvidenceService.readEvidence(directory,
                new TrainingEvidenceService.Reference("schema.sql", line), revision)).isInstanceOf(Exception.class);
        Files.createSymbolicLink(directory.resolve("linked.sql"), directory.resolve("schema.sql"));
        assertThatThrownBy(() -> TrainingEvidenceService.readEvidence(directory,
                new TrainingEvidenceService.Reference("linked.sql", 1), revision)).isInstanceOf(Exception.class);
    }

    @Test void doesNotExposeTransportExceptions() throws Exception {
        approved();
        when(git.checkoutSnapshot(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("private-customer-value"));
        assertThatThrownBy(() -> service.verifiedCase(jobId, userId, sample(3)))
                .isInstanceOf(TrainingTransferClient.CaseValidationException.class).hasMessageNotContaining("private-customer-value");
    }

    @Test void keepsFileOnlyEvidenceAtFileScopeWithoutInventingLine() {
        approved();
        var submitted = new LinkedHashMap<>(sample(1));
        submitted.put("answers", Map.of("general", Map.of("evidences", List.of(Map.of("file", "schema.sql")))));
        var checked = service.verifiedCase(jobId, userId, submitted);
        assertThat(checked.get("answers")).isEqualTo(submitted.get("answers"));
        verifyNoInteractions(git);
    }

    @Test void verifiesUnknownFileAtPinnedCommitWithoutCreatingExcerptOrLine() throws Exception {
        approved();
        var root = Files.createDirectory(directory.resolve("file-checkout"));
        Files.writeString(root.resolve("extra.sql"), "select 1;\n");
        when(git.checkoutSnapshot(any(), any(), any(), any(), any()))
                .thenReturn(new GitRepositoryConnectionService.GitSnapshot(root, root));
        var submitted = new LinkedHashMap<>(sample(1));
        submitted.put("answers", Map.of("general", Map.of("evidences", List.of(Map.of("file", "extra.sql")))));
        var checked = service.verifiedCase(jobId, userId, submitted);
        var source = (Map<?, ?>) ((Map<?, ?>) checked.get("request")).get("sourceSummary");
        var ref = (Map<?, ?>) ((List<?>) source.get("reviewedEvidence")).getFirst();
        assertThat(ref.get("scope")).isEqualTo("FILE");
        assertThat(ref.get("fileSha256").toString()).matches("[0-9a-f]{64}");
        assertThat(ref.containsKey("line")).isFalse();
        assertThat(ref.containsKey("excerpt")).isFalse();
        assertThat(checked.get("answers")).isEqualTo(submitted.get("answers"));
        assertThat(root).doesNotExist();
    }

    @Test void rejectsMalformedLinesEvenForKnownFile() {
        approved();
        for (Object line : List.of(0, -1, true, "1", 1.5)) {
            var submitted = new LinkedHashMap<>(sample(1));
            submitted.put("answers", Map.of("general", Map.of("evidences", List.of(Map.of("file", "schema.sql", "line", line)))));
            assertThatThrownBy(() -> service.verifiedCase(jobId, userId, submitted)).isInstanceOf(ResponseStatusException.class);
        }
        verifyNoInteractions(git);
    }
}
