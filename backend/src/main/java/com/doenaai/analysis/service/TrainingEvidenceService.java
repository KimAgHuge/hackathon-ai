package com.doenaai.analysis.service;

import com.doenaai.analysis.dto.TrainingCaseDraftResponse;
import com.doenaai.analysis.repository.AnalysisJobRepository;
import com.doenaai.project.connection.service.GitRepositoryConnectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** 원본 분석은 바꾸지 않고 고정 Git 커밋에서 확인한 학습용 보완 근거만 만든다. */
@Service
@RequiredArgsConstructor
public class TrainingEvidenceService {
    private final AnalysisResultService results;
    private final AnalysisJobRepository jobs;
    private final GitRepositoryConnectionService git;

    public TrainingCaseDraftResponse supplement(UUID jobId, UUID userId, Map<String, Object> submitted) {
        var value = verifiedCase(jobId, userId, submitted);
        value.put("humanApproved", false);
        value.put("sanitized", false);
        return new TrainingCaseDraftResponse(value,
                List.of("확인한 파일·코드 근거와 정답의 일치 및 비식별화를 다시 검토해 주세요. 파일 단위 인용은 코드 줄의 동작을 증명하지 않습니다."),
                List.of(object(object(value.get("request")).get("sourceSummary")).containsKey("reviewedEvidence")
                        ? "request.sourceSummary.reviewedEvidence: 분석 당시 Git 커밋에서 확인한 보완 근거입니다. 학습 사례 사본에만 추가했습니다."
                        : "기존 모델 입력에서 파일·줄 참조를 확인했습니다. 임의 줄 번호나 코드 조각을 추가하지 않았습니다."));
    }

    /** 전송 시에도 보완 근거를 다시 검증해 브라우저에서 만든 출처 표시는 신뢰하지 않는다. */
    public Map<String, Object> verifiedCase(UUID jobId, UUID userId, Map<String, Object> submitted) {
        var expected = results.trainingDraft(jobId, userId).caseDraft();
        if (submitted == null || !Objects.equals(expected.get("caseId"), submitted.get("caseId"))) throw invalid();
        var expectedRequest = object(expected.get("request"));
        var expectedSnapshot = object(expected.get("snapshot"));
        var request = new LinkedHashMap<>(object(submitted.get("request")));
        var snapshot = object(submitted.get("snapshot"));
        if (!Objects.equals(expectedRequest.get("projectId"), request.get("projectId"))
                || !Objects.equals(expectedSnapshot.get("requirementVersionId"), snapshot.get("requirementVersionId"))
                || !Objects.equals(expectedSnapshot.get("sourceRevision"), snapshot.get("sourceRevision"))) throw invalid();
        var source = new LinkedHashMap<>(object(request.get("sourceSummary")));
        if (!Objects.equals(snapshot.get("sourceRevision"), source.get("commitSha"))) throw invalid();
        source.remove("reviewedEvidence");
        request.put("sourceSummary", source);
        var value = new LinkedHashMap<>(submitted);
        value.put("request", request);
        var originalReferences = new HashSet<Reference>();
        var originalSource = new LinkedHashMap<>(object(expectedRequest.get("sourceSummary")));
        originalSource.remove("reviewedEvidence");
        originalSource.remove("changeRequirement");
        collect(originalSource, originalReferences);
        collect(expectedRequest.get("relations"), originalReferences);
        var general = object(object(submitted.get("answers")).get("general"));
        if (!(general.get("evidences") instanceof List<?> evidences) || evidences.size() > 100) throw invalid();
        var missing = new LinkedHashSet<Reference>();
        for (Object item : evidences) {
            var reference = reference(object(item));
            if (reference == null) throw invalid();
            if (!originalReferences.contains(reference)) missing.add(reference);
        }
        if (missing.isEmpty()) return value;
        if (missing.size() > 20) throw new TrainingTransferClient.CaseValidationException("EVIDENCE_LIMIT", "answers.general");
        var saved = jobs.findTrainingSource(jobId).orElseThrow(TrainingEvidenceService::invalid);
        if (!Objects.equals(saved.revision(), snapshot.get("sourceRevision")) || saved.revision() == null
                || !saved.revision().matches("[0-9a-f]{40}") || saved.url() == null || saved.branch() == null) throw invalid();
        try (var checkout = git.checkoutSnapshot(UUID.fromString(expectedRequest.get("projectId").toString()), userId,
                saved.url(), saved.branch(), saved.revision())) {
            var verified = new ArrayList<Map<String, Object>>();
            for (var reference : missing) verified.add(readEvidence(checkout.path(), reference, saved.revision()));
            source.put("reviewedEvidence", verified);
            return value;
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            // Git·파일 예외에는 주소나 비밀값이 섞일 수 있어 원문과 스택을 내보내지 않는다.
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new TrainingTransferClient.CaseValidationException("EVIDENCE_SOURCE_UNAVAILABLE", "answers.general");
        }
    }

    /** 파일 단위는 내용 해시만, 줄 단위는 해당 줄의 작은 조각만 반환한다. 경로·파일 제한은 동일하다. */
    static Map<String, Object> readEvidence(Path root, Reference reference, String revision) throws Exception {
        var path = reference.file();
        if (path.isBlank() || path.length() > 500 || path.contains("\\") || path.contains(":")) throw invalid();
        var relative = Path.of(path);
        if (relative.isAbsolute() || !relative.normalize().equals(relative)
                || Arrays.stream(path.split("/")).anyMatch(part -> part.startsWith(".") || part.equals(".."))) throw invalid();
        var actualRoot = root.toRealPath();
        var file = actualRoot.resolve(relative).normalize();
        if (!file.startsWith(actualRoot) || Files.isSymbolicLink(file) || !Files.isRegularFile(file)
                || !file.toRealPath().startsWith(actualRoot) || Files.size(file) > 262_144
                || path.toLowerCase(Locale.ROOT).matches(".*\\.(pem|key|p12|pfx|keystore)$")) throw invalid();
        if (reference.line() == null) {
            var content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.matches("(?s).*-----BEGIN .*PRIVATE KEY-----.*")) throw invalid();
            return Map.of("file", path, "sourceRevision", revision, "scope", "FILE",
                    "fileSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))),
                    "origin", "VERIFIED_GIT_SNAPSHOT");
        }
        var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (reference.line() < 1 || reference.line() > lines.size()) throw invalid();
        int start = Math.max(1, reference.line() - 2), end = Math.min(lines.size(), reference.line() + 2);
        var excerpt = String.join("\n", lines.subList(start - 1, end));
        if (excerpt.length() > 4000 || excerpt.matches("(?s).*-----BEGIN .*PRIVATE KEY-----.*")) throw invalid();
        return Map.of("file", path, "line", reference.line(), "sourceRevision", revision,
                "excerptStartLine", start, "excerptEndLine", end, "excerpt", excerpt,
                "excerptSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(excerpt.getBytes(StandardCharsets.UTF_8))),
                "origin", "VERIFIED_GIT_SNAPSHOT");
    }

    private static void collect(Object value, Set<Reference> references) {
        if (value instanceof Map<?, ?> map) {
            var path = map.get("file") instanceof String name ? name : map.get("path");
            if (path instanceof String file && !file.isBlank()) references.add(new Reference(file, null));
            var ref = reference(map);
            if (ref != null) references.add(ref);
            map.values().forEach(nested -> collect(nested, references));
        } else if (value instanceof List<?> list) list.forEach(nested -> collect(nested, references));
    }

    private static Reference reference(Map<?, ?> value) {
        var path = value.getOrDefault("file", null);
        if (path == null) path = value.get("path");
        if (!(path instanceof String file) || file.isBlank()) return null;
        if (value.get("line") == null) return new Reference(file, null);
        if (value.get("line") instanceof Number number
                && Double.isFinite(number.doubleValue()) && number.doubleValue() == number.intValue() && number.intValue() > 0) {
            return new Reference(file, number.intValue());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw invalid();
        return (Map<String, Object>) map;
    }

    private static ResponseStatusException invalid() {
        return new TrainingTransferClient.CaseValidationException("EVIDENCE_INVALID_REFERENCE", "caseDraft");
    }

    record Reference(String file, Integer line) {}
}
