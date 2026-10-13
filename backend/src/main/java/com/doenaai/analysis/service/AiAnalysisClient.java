package com.doenaai.analysis.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class AiAnalysisClient {
    static final int MAX_RESPONSE_BYTES = 1_000_000;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient client;

    @Autowired
    public AiAnalysisClient(ObjectMapper mapper,
                            @Value("${doenaai.ai-server.base-url:http://localhost:8000}") String baseUrl,
                            @Value("${doenaai.ai-server.internal-api-key:}") String apiKey,
                            @Value("${doenaai.ai-server.timeout-seconds:660}") int timeoutSeconds) {
        this(mapper, baseUrl, apiKey, Duration.ofSeconds(timeoutSeconds));
    }

    AiAnalysisClient(ObjectMapper mapper, String baseUrl, String apiKey, Duration timeout) {
        this.mapper = mapper;
        this.endpoint = endpointFor(baseUrl);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("AI timeout must be positive.");
        }
        this.apiKey = apiKey;
        this.timeout = timeout;
        // Uvicorn's HTTP/1.1 parser cannot safely consume h2c upgrade requests with a JSON body.
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * @param payload Git·DB 근거와 요구사항을 담은, 비밀정보 검사가 끝난 입력
     * @return FastAPI가 돌려준 공통 분석 및 databaseImpact JSON
     * @throws IOException 연결·시간 제한·응답 형식 검사 실패 시 발생
     */
    public Map<String, Object> analyze(Map<String, Object> payload) throws IOException, InterruptedException {
        if (apiKey == null || apiKey.length() < 32) throw new IOException("Internal AI key is not configured.");
        CompletableFuture<HttpResponse<String>> exchange;
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Api-Key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            exchange = client.sendAsync(request, response -> new BoundedBodySubscriber(response.statusCode()));
        } catch (RuntimeException exception) {
            throw new IOException("Could not prepare AI analysis request.");
        }

        HttpResponse<String> response;
        try {
            // JDK request timeout ends at response headers. This deadline also covers the entire body.
            response = exchange.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            exchange.cancel(true);
            throw new IOException("AI analysis request timed out.");
        } catch (InterruptedException exception) {
            exchange.cancel(true);
            throw exception;
        } catch (ExecutionException exception) {
            throw safeFailure(exception);
        }

        Map<String, Object> result;
        try {
            result = mapper.readValue(response.body(), new TypeReference<>() {});
        } catch (RuntimeException exception) {
            // JSON parser exceptions may quote remote body fragments, so never retain their cause.
            throw new IOException("Invalid AI result JSON.");
        }
        validate(result, payload);
        return result;
    }

    /** 결과 필드와 분석 종류별 DB 판단 규칙을 확인하고, 맞지 않으면 거부한다. */
    static void validate(Map<String, Object> result, Map<String, Object> payload) throws IOException {
        if (result == null || !(result.get("summary") instanceof String summary) || summary.isBlank()
                || !(result.get("riskLevel") instanceof String risk)
                || !Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL", "UNKNOWN").contains(risk)
                || !(result.get("riskScore") instanceof Number score)
                || score.doubleValue() != score.intValue() || score.intValue() < 0 || score.intValue() > 100) {
            throw new IOException("Invalid AI result contract.");
        }
        for (String name : List.of("frameworks", "impactAreas", "recommendations")) {
            if (!(result.get(name) instanceof List<?> items) || items.stream().anyMatch(v -> !(v instanceof String))) {
                throw new IOException("Invalid AI result list.");
            }
        }
        if (!(result.get("evidences") instanceof List<?> items)
                || items.stream().anyMatch(v -> !(v instanceof Map<?, ?>))) {
            throw new IOException("Invalid AI evidence list.");
        }
        if (!(result.get("databaseImpact") instanceof Map<?, ?> databaseImpact)
                || !(databaseImpact.get("status") instanceof String status)
                || !Set.of("CHANGE_REQUIRED", "NO_CHANGE", "NEEDS_CLARIFICATION", "UNKNOWN", "NOT_APPLICABLE").contains(status)
                || !(databaseImpact.get("explanation") instanceof String explanation) || explanation.isBlank()
                || !stringList(databaseImpact.get("observedTables"))
                || !stringList(databaseImpact.get("openQuestions"))
                || !(databaseImpact.get("conditionalPaths") instanceof List<?> paths)
                || paths.stream().anyMatch(path -> !(path instanceof Map<?, ?> entry)
                        || !(entry.get("condition") instanceof String condition) || condition.isBlank()
                        || !(entry.get("databaseEffect") instanceof String effect) || effect.isBlank())
                || !(databaseImpact.get("proposedChanges") instanceof List<?> changes)
                || changes.stream().anyMatch(change -> !(change instanceof Map<?, ?> entry)
                        || !(entry.get("targetTable") instanceof String table) || table.isBlank()
                        || !entry.containsKey("targetColumn")
                        || (entry.get("targetColumn") != null
                            && (!(entry.get("targetColumn") instanceof String column) || column.isBlank()))
                        || !(entry.get("changeType") instanceof String type) || type.isBlank()
                        || (Set.of("ADD_COLUMN", "ALTER_COLUMN", "DROP_COLUMN").contains(entry.get("changeType"))
                            && !(entry.get("targetColumn") instanceof String))
                        || !(entry.get("description") instanceof String description) || description.isBlank()
                        || !(entry.get("rationale") instanceof String rationale) || rationale.isBlank())) {
            throw new IOException("Invalid AI database impact contract.");
        }
        boolean requirementAnalysis = payload.get("sourceSummary") instanceof Map<?, ?> source
                && source.get("changeRequirement") instanceof Map<?, ?>;
        if ((!requirementAnalysis && !"NOT_APPLICABLE".equals(status))
                || ("CHANGE_REQUIRED".equals(status) && changes.isEmpty())
                || ("NEEDS_CLARIFICATION".equals(status)
                    && (((List<?>) databaseImpact.get("openQuestions")).isEmpty()
                        || ((List<?>) databaseImpact.get("conditionalPaths")).size() < 2))
                || (!"CHANGE_REQUIRED".equals(status) && !changes.isEmpty())
                || ("NOT_APPLICABLE".equals(status)
                    && (!((List<?>) databaseImpact.get("observedTables")).isEmpty()
                        || !((List<?>) databaseImpact.get("conditionalPaths")).isEmpty()
                        || !((List<?>) databaseImpact.get("openQuestions")).isEmpty()))) {
            throw new IOException("Invalid AI database impact decision.");
        }
    }

    private static boolean stringList(Object value) {
        return value instanceof List<?> items && items.stream().allMatch(item -> item instanceof String);
    }

    private static URI endpointFor(String baseUrl) {
        try {
            URI endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/api/analysis/project");
            if (!Set.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null
                    || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return endpoint;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid internal AI server URL.");
        }
    }

    private static IOException safeFailure(Throwable exception) {
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < 10; depth++, cause = cause.getCause()) {
            if (cause instanceof SafeResponseException safe) return new IOException(safe.getMessage());
            if (cause instanceof HttpTimeoutException) return new IOException("AI analysis request timed out.");
        }
        return new IOException("AI analysis request failed.");
    }

    /** Bounds bytes before copying any network chunk and cancels immediately on rejection. */
    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<String> {
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int status;
        private Flow.Subscription subscription;

        private BoundedBodySubscriber(int status) {
            this.status = status;
        }

        @Override
        public CompletionStage<String> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (this.subscription != null) {
                subscription.cancel();
                return;
            }
            this.subscription = subscription;
            if (status != 200) {
                reject("AI server rejected analysis (HTTP " + status + ").");
            } else {
                subscription.request(1);
            }
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            long incomingBytes = buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if (incomingBytes > MAX_RESPONSE_BYTES - bytes.size()) {
                reject("AI response exceeds size limit.");
                return;
            }
            for (ByteBuffer buffer : buffers) {
                byte[] chunk = new byte[Math.min(buffer.remaining(), 8192)];
                while (buffer.hasRemaining()) {
                    int count = Math.min(buffer.remaining(), chunk.length);
                    buffer.get(chunk, 0, count);
                    bytes.write(chunk, 0, count);
                }
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable exception) {
            body.completeExceptionally(new SafeResponseException("AI response body could not be read."));
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toString(StandardCharsets.UTF_8));
        }

        private void reject(String safeMessage) {
            // Complete first so a synchronous cancellation callback cannot replace the safe reason.
            body.completeExceptionally(new SafeResponseException(safeMessage));
            subscription.cancel();
        }
    }

    private static final class SafeResponseException extends IOException {
        private SafeResponseException(String message) {
            super(message);
        }
    }
}
