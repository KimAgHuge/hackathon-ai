package com.doenaai.analysis.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.function.Consumer;

@Service
public class AiAnalysisClient {
    static final int MAX_RESPONSE_BYTES = 1_000_000;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final URI streamEndpoint;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient client;

    @Autowired
    public AiAnalysisClient(ObjectMapper mapper,
                            @Value("${doenaai.ai-server.base-url:http://localhost:8000}") String baseUrl,
                            @Value("${doenaai.ai-server.internal-api-key:}") String apiKey,
                            @Value("${doenaai.ai-server.timeout-seconds:3900}") int timeoutSeconds) {
        this(mapper, baseUrl, apiKey, Duration.ofSeconds(timeoutSeconds));
    }

    AiAnalysisClient(ObjectMapper mapper, String baseUrl, String apiKey, Duration timeout) {
        this.mapper = mapper;
        this.endpoint = endpointFor(baseUrl);
        this.streamEndpoint = URI.create(endpoint + "/stream");
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

    /** AI 분석/추론 서버의 검증된 단계 이벤트를 받으면서 최종 결과를 기다린다. 구버전 서버의 404는 기존 API로 처리한다. */
    public Map<String, Object> analyzeWithProgress(Map<String, Object> payload,
                                                    Consumer<ProgressEvent> onProgress)
            throws IOException, InterruptedException {
        if (apiKey == null || apiKey.length() < 32) throw new IOException("Internal AI key is not configured.");
        long started = System.nanoTime();
        CompletableFuture<HttpResponse<InputStream>> exchange;
        try {
            var request = HttpRequest.newBuilder(streamEndpoint).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/x-ndjson")
                    .header("X-Internal-Api-Key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            exchange = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (RuntimeException exception) {
            throw new IOException("Could not prepare AI analysis request.");
        }
        HttpResponse<InputStream> response;
        try {
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
        if (response.statusCode() == 404) {
            response.body().close();
            return analyze(payload);
        }
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("AI 분석/추론 서버가 분석 요청을 거부했습니다(HTTP " + response.statusCode() + ").");
        }
        CompletableFuture<Map<String, Object>> parsed = new CompletableFuture<>();
        Thread reader = Thread.ofVirtual().name("ai-analysis-stream-reader").start(() -> {
            try (InputStream body = response.body()) {
                parsed.complete(readStream(body, payload, onProgress));
            } catch (IOException exception) {
                parsed.completeExceptionally(exception);
            } catch (RuntimeException exception) {
                parsed.completeExceptionally(new IOException("Invalid AI progress stream."));
            }
        });
        try {
            long remaining = Math.max(1, timeout.toNanos() - (System.nanoTime() - started));
            return parsed.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            reader.interrupt();
            response.body().close();
            throw new IOException("AI analysis request timed out.");
        } catch (InterruptedException exception) {
            reader.interrupt();
            response.body().close();
            throw exception;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException safe) throw safe;
            throw new IOException("AI analysis request failed.");
        }
    }

    public record ProgressEvent(String code, Integer attempt) {}

    private Map<String, Object> readStream(InputStream body, Map<String, Object> payload,
                                           Consumer<ProgressEvent> onProgress) throws IOException {
        try (var lines = new BufferedReader(new InputStreamReader(new BoundedInputStream(body), StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                Map<String, Object> event;
                try {
                    event = mapper.readValue(line, new TypeReference<>() {});
                } catch (RuntimeException exception) {
                    throw new IOException("Invalid AI progress stream.");
                }
                if (event == null) throw new IOException("Invalid AI progress stream.");
                if ("heartbeat".equals(event.get("type"))) continue;
                if ("progress".equals(event.get("type"))) {
                    if (!(event.get("code") instanceof String code)
                            || !Set.of("GENERAL_STARTED", "GENERAL_COMPLETED", "DATABASE_STARTED",
                                    "DATABASE_COMPLETED", "DATABASE_SKIPPED", "VALIDATION_RETRY").contains(code)
                            || (event.containsKey("attempt") && (!(event.get("attempt") instanceof Number attempt)
                                || attempt.doubleValue() != attempt.intValue()
                                || attempt.intValue() < 1 || attempt.intValue() > 10))) {
                        throw new IOException("Invalid AI progress event.");
                    }
                    if (onProgress != null) {
                        try {
                            onProgress.accept(new ProgressEvent(code,
                                    event.get("attempt") instanceof Number number ? number.intValue() : null));
                        } catch (RuntimeException ignored) {
                            // 표시용 이벤트 기록 오류가 최종 분석 결과를 버리게 하지 않는다.
                        }
                    }
                    continue;
                }
                if ("error".equals(event.get("type"))) {
                    throw new IOException(switch (event.get("code") instanceof String code ? code : "") {
                        case "MODEL_TIMEOUT" -> "Ollama inference timed out.";
                        case "MODEL_UNAVAILABLE" -> "Ollama is unavailable.";
                        case "INVALID_MODEL_RESPONSE" -> "The model response did not match the required schema.";
                        default -> "AI analysis failed.";
                    });
                }
                if ("result".equals(event.get("type"))) {
                    if (!(event.get("result") instanceof Map<?, ?> raw)) {
                        throw new IOException("Invalid AI result JSON.");
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = (Map<String, Object>) raw;
                    validate(result, payload);
                    return result;
                }
                throw new IOException("Invalid AI progress stream.");
            }
        } catch (SafeResponseException exception) {
            throw exception;
        } catch (IOException exception) {
            if (Set.of("Invalid AI progress stream.", "Invalid AI progress event.",
                    "Invalid AI result JSON.", "Invalid AI result contract.", "Invalid AI risk assessment.", "Invalid AI plain-language summary.",
                    "Invalid AI database impact contract.", "Invalid AI database impact decision.",
                    "Ollama inference timed out.", "Ollama is unavailable.",
                    "The model response did not match the required schema.", "AI analysis failed.")
                    .contains(exception.getMessage())) throw exception;
            throw new IOException("AI response body could not be read.");
        }
        throw new IOException("AI response ended without result.");
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private int count;
        private BoundedInputStream(InputStream input) { super(input); }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value != -1 && ++count > MAX_RESPONSE_BYTES) throw new SafeResponseException("AI response exceeds size limit.");
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int size = super.read(bytes, offset, length);
            if (size > 0 && (count += size) > MAX_RESPONSE_BYTES) throw new SafeResponseException("AI response exceeds size limit.");
            return size;
        }
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
        if (!RiskScorePolicy.matches(risk, score.intValue(), result.get("riskAssessment"))) {
            throw new IOException("Invalid AI risk assessment.");
        }
        // 구버전 AI 분석/추론 서버와 저장 결과는 허용하되, 새 필드가 있으면 한 줄 계약을 지킨다.
        if (result.containsKey("plainLanguageSummary")
                && (!(result.get("plainLanguageSummary") instanceof String plainSummary)
                    || plainSummary.isBlank() || plainSummary.length() > 200
                    || plainSummary.contains("\n") || plainSummary.contains("\r"))) {
            throw new IOException("Invalid AI plain-language summary.");
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
            throw new IllegalArgumentException("AI 분석/추론 서버의 내부 주소가 올바르지 않습니다.");
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
                reject("AI 분석/추론 서버가 분석 요청을 거부했습니다(HTTP " + status + ").");
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
