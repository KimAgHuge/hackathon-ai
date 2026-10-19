package com.doenaai.analysis.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

@Service
public class TrainingTransferClient {
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String apiKey;
    private final HttpClient client;

    /** 검증 오류는 고정 코드와 안내만 보존하고 원격 원문을 포함하지 않는다. */
    public static final class CaseValidationException extends ResponseStatusException {
        public final String code;
        public final String field;
        public CaseValidationException(String code, String field) {
            super(HttpStatus.UNPROCESSABLE_ENTITY, switch (code) {
                case "GROUNDING_MISMATCH" -> "승인 정답의 근거가 학습 입력과 일치하지 않습니다. 원본 커밋의 근거 확인·보강 후 다시 검토해 주세요.";
                case "CASE_RESPONSE_INVALID" -> "단계별 정답의 JSON 형식이 올바르지 않습니다.";
                case "CASE_NOT_SANITIZED" -> "비밀정보 또는 비식별화가 필요한 문자열이 있습니다. 원문을 검토해 주세요.";
                case "EVIDENCE_SOURCE_UNAVAILABLE" -> "분석 당시 Git 커밋을 확인하지 못했습니다. 저장소 연결·커밋·파일과 줄 번호를 확인해 주세요.";
                case "EVIDENCE_INVALID_REFERENCE" -> "학습 사례의 분석 연결 정보나 근거 파일·줄 번호가 올바르지 않습니다.";
                case "EVIDENCE_LIMIT" -> "보완할 근거가 20개를 초과했습니다. 정답 근거를 검토하거나 새 분석을 사용해 주세요.";
                default -> "학습 사례의 필수 항목과 승인·스냅샷 정보를 확인해 주세요.";
            });
            this.code = code;
            this.field = field;
        }
    }

    public TrainingTransferClient(ObjectMapper mapper,
                                  @Value("${doenaai.ai-server.base-url:http://localhost:8000}") String baseUrl,
                                  @Value("${doenaai.ai-server.internal-api-key:}") String apiKey) {
        this.mapper = mapper;
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/api/training/transfers");
        this.apiKey = apiKey;
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** 내부 인증을 붙여 AI 분석/추론 서버에 게시하고 내용 대신 전송 상태만 반환한다. */
    public Map<String, Object> publish(UUID jobId, Map<String, Object> caseDraft) {
        try {
            var body = mapper.writeValueAsString(Map.of("job_id", jobId.toString(), "case_draft", caseDraft));
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json").header("X-Internal-Api-Key", key())
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            return exchange(request);
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            throw unavailable();
        }
    }

    /** 파일 게시와 수신 알림 없이 사례 계약만 검사한다. */
    public Map<String, Object> validateCase(UUID jobId, Map<String, Object> caseDraft) {
        try {
            var body = mapper.writeValueAsString(Map.of("job_id", jobId.toString(), "case_draft", caseDraft));
            return exchange(HttpRequest.newBuilder(URI.create(endpoint + "/validate"))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                    .header("X-Internal-Api-Key", key()).POST(HttpRequest.BodyPublishers.ofString(body)).build());
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            throw unavailable();
        }
    }

    /** 같은 작업의 수신 상태를 확인하며 실패한 알림의 재시도를 요청한다. */
    public Map<String, Object> status(UUID jobId) {
        try {
            var request = HttpRequest.newBuilder(URI.create(endpoint + "/by-job/" + jobId))
                    .timeout(Duration.ofSeconds(15)).header("X-Internal-Api-Key", key()).GET().build();
            return exchange(request);
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            throw unavailable();
        }
    }

    private String key() {
        if (apiKey == null || apiKey.length() < 32) throw unavailable();
        return apiKey;
    }

    private Map<String, Object> exchange(HttpRequest request) throws Exception {
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "학습 전송 내역이 없습니다.");
        }
        if (response.statusCode() == 422) {
            String code = "CASE_CONTRACT_INVALID", field = "caseDraft";
            try {
                var detail = mapper.readTree(response.body()).path("detail");
                String supplied = detail.path("code").asText("");
                if (Set.of("GROUNDING_MISMATCH", "CASE_RESPONSE_INVALID", "CASE_NOT_SANITIZED", "CASE_CONTRACT_INVALID").contains(supplied)) code = supplied;
                String suppliedField = detail.path("field").asText("");
                if (Set.of("caseDraft", "answers.general", "answers.database").contains(suppliedField)) field = suppliedField;
            } catch (Exception ignored) {
                // 알 수 없는 원격 응답과 원문은 사용자 안내에 포함하지 않는다.
            }
            throw new CaseValidationException(code, field);
        }
        if (response.statusCode() != 200) throw unavailable();
        return mapper.readValue(response.body(), new TypeReference<>() {});
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "학습 전송 서버에 연결할 수 없습니다. 잠시 후 다시 확인하세요.");
    }
}
