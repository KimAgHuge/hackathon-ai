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

@Service
public class TrainingTransferClient {
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String apiKey;
    private final HttpClient client;

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

    /** 같은 작업의 수신 상태를 확인하며 AI 분석/추론 서버가 실패한 알림을 재시도하게 한다. */
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
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "학습 사례 형식·근거 또는 비식별화를 확인하세요.");
        }
        if (response.statusCode() != 200) throw unavailable();
        return mapper.readValue(response.body(), new TypeReference<>() {});
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "학습 전송 서버에 연결할 수 없습니다. 잠시 후 다시 확인하세요.");
    }
}
