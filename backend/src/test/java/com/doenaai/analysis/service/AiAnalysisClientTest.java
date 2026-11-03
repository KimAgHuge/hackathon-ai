package com.doenaai.analysis.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class AiAnalysisClientTest {
    private static final String API_KEY = "integration-only-test-key-not-a-real-credential";
    private static final String VALID_RESULT = """
            {"summary":"테스트 분석 결과","plainLanguageSummary":"변경 범위를 확인한 결과입니다.","riskLevel":"LOW","riskScore":7,
             "frameworks":["Spring Boot"],"impactAreas":["API"],
             "recommendations":["검증"],"evidences":[{"source":"test"}],
             "databaseImpact":{"status":"NOT_APPLICABLE","explanation":"변경 요구사항이 없습니다.",
             "observedTables":[],"proposedChanges":[],"conditionalPaths":[],"openQuestions":[]}}
            """;
    private static final Map<String, Object> PAYLOAD = Map.of(
            "projectId", "fixture-project", "sourceSummary", Map.of("components", List.of()),
            "databaseSummary", Map.of("tables", List.of()), "relations", List.of());

    private HttpServer server;
    private ExecutorService handlers;

    @Test
    @SuppressWarnings("unchecked")
    void validatesIndexAndDuplicateSemanticsInReceivedResults() throws Exception {
        Map<String, Object> result = JsonMapper.builder().build().readValue(VALID_RESULT, Map.class);
        var column = Map.<String, Object>of("targetTable", "example.table_example", "targetColumn", "column_example",
                "changeType", "ADD_COLUMN", "necessity", "REQUIRED", "description", "컬럼 추가입니다.", "rationale", "명시한 요구사항입니다.");
        var index = Map.<String, Object>of("targetTable", "example.table_example", "targetColumn", "column_example",
                "changeType", "ADD_INDEX", "necessity", "RECOMMENDED", "description", "인덱스 추가 검토입니다.", "rationale", "검색 비용 확인이 필요합니다.");
        var impact = new java.util.LinkedHashMap<String, Object>(Map.of("status", "CHANGE_REQUIRED", "explanation", "컬럼 추가가 필요합니다.",
                "observedTables", List.of("example.table_example"), "proposedChanges", List.of(column, index),
                "conditionalPaths", List.of(), "openQuestions", List.of()));
        result.put("databaseImpact", impact);
        var payload = Map.<String, Object>of("sourceSummary", Map.of("changeRequirement", Map.of("title", "가상 컬럼 추가")));
        AiAnalysisClient.validate(result, payload);
        impact.put("proposedChanges", List.of(column, column));
        assertThatThrownBy(() -> AiAnalysisClient.validate(result, payload)).isInstanceOf(IOException.class);
        impact.put("proposedChanges", List.of(index));
        assertThatThrownBy(() -> AiAnalysisClient.validate(result, payload)).isInstanceOf(IOException.class);
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        handlers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(handlers);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        server.stop(0);
        handlers.shutdownNow();
        assertThat(handlers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void sendsAuthenticatedJsonAndReadsStructuredResult() throws Exception {
        AtomicReference<String> receivedKey = new AtomicReference<>();
        AtomicReference<String> receivedContentType = new AtomicReference<>();
        AtomicReference<String> receivedMethod = new AtomicReference<>();
        AtomicReference<String> receivedProtocol = new AtomicReference<>();
        AtomicReference<List<String>> receivedUpgrade = new AtomicReference<>();
        AtomicReference<List<String>> receivedHttp2Settings = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();
        serve(exchange -> {
            receivedKey.set(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"));
            receivedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            receivedMethod.set(exchange.getRequestMethod());
            receivedProtocol.set(exchange.getProtocol());
            receivedUpgrade.set(exchange.getRequestHeaders().get("Upgrade"));
            receivedHttp2Settings.set(exchange.getRequestHeaders().get("HTTP2-Settings"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, VALID_RESULT);
        });

        Map<String, Object> result = client(Duration.ofSeconds(3)).analyze(PAYLOAD);

        assertThat(result).containsEntry("summary", "테스트 분석 결과")
                .containsEntry("plainLanguageSummary", "변경 범위를 확인한 결과입니다.")
                .containsEntry("riskLevel", "LOW").containsEntry("riskScore", 7);
        assertThat(receivedKey.get()).isEqualTo(API_KEY);
        assertThat(receivedContentType.get()).isEqualTo("application/json");
        assertThat(receivedMethod.get()).isEqualTo("POST");
        assertThat(receivedProtocol.get()).isEqualTo("HTTP/1.1");
        assertThat(receivedUpgrade.get()).as("HTTP/2 upgrade must not be requested").isNull();
        assertThat(receivedHttp2Settings.get()).as("HTTP/2 settings must not be sent").isNull();
        JsonMapper mapper = JsonMapper.builder().build();
        assertThat(mapper.readTree(receivedBody.get()))
                .isEqualTo(mapper.readTree(mapper.writeValueAsString(PAYLOAD)));
    }

    @Test
    void thinkingUnsupportedErrorIsClassifiedWithoutDowngradingTheRequestedMode() {
        var failure = new AiAnalysisClient.ModelFailureException("THINKING_NOT_SUPPORTED");

        assertThat(failure.code()).isEqualTo("THINKING_NOT_SUPPORTED");
        assertThat(failure.getMessage()).contains("추론 모드를 지원하지 않습니다");
    }

    @Test
    void thinkingEffortMismatchIsClassifiedWithoutExposingRemoteDetails() {
        var failure = new AiAnalysisClient.ModelFailureException("THINKING_EFFORT_MISMATCH");

        assertThat(failure.code()).isEqualTo("THINKING_EFFORT_MISMATCH");
        assertThat(failure.getMessage()).contains("추론 강도", "분석 결과를 저장하지 않았습니다");
    }

    @Test
    void unauthorizedResponseFailsWithoutExposingRemoteBody() {
        serve(exchange -> respond(exchange, 401, "remote-body-secret-token"));

        assertThatThrownBy(() -> client(Duration.ofSeconds(3)).analyze(PAYLOAD))
                .isInstanceOf(IOException.class)
                .hasMessage("AI 분석/추론 서버가 분석 요청을 거부했습니다(HTTP 401).")
                .hasNoCause();
    }

    @Test
    void invalidResultSchemaFailsSafely() {
        serve(exchange -> respond(exchange, 200,
                "{\"summary\":\"remote-body-secret-token\",\"riskLevel\":\"LOW\",\"riskScore\":101}"));

        assertThatThrownBy(() -> client(Duration.ofSeconds(3)).analyze(PAYLOAD))
                .isInstanceOf(IOException.class)
                .hasMessage("Invalid AI result contract.")
                .hasNoCause();
    }

    @Test
    void contradictoryRiskLevelAndScoreAreRejected() {
        serve(exchange -> respond(exchange, 200,
                VALID_RESULT.replace("\"riskLevel\":\"LOW\"", "\"riskLevel\":\"HIGH\"")));

        assertThatThrownBy(() -> client(Duration.ofSeconds(3)).analyze(PAYLOAD))
                .isInstanceOf(IOException.class)
                .hasMessage("Invalid AI risk assessment.");
    }

    @Test
    void riskBreakdownMustAddUpToDisplayedScore() {
        String assessment = """
                "riskAssessment":{"changeScope":{"score":5,"reason":"범위"},
                "dataImpact":{"score":0,"reason":"데이터 영향 없음"},
                "securityImpact":{"score":0,"reason":"보안 영향 없음"},
                "deliveryComplexity":{"score":0,"reason":"배포 영향 없음"},
                "uncertainty":{"score":0,"reason":"불확실성 없음"}}
                """;
        serve(exchange -> respond(exchange, 200,
                VALID_RESULT.replace("\"databaseImpact\":", assessment + ",\"databaseImpact\":")));

        assertThatThrownBy(() -> client(Duration.ofSeconds(3)).analyze(PAYLOAD))
                .isInstanceOf(IOException.class)
                .hasMessage("Invalid AI risk assessment.");
    }

    @Test
    void invalidPlainLanguageSummaryIsRejected() {
        serve(exchange -> respond(exchange, 200,
                VALID_RESULT.replace("변경 범위를 확인한 결과입니다.", " ")));

        assertThatThrownBy(() -> client(Duration.ofSeconds(3)).analyze(PAYLOAD))
                .isInstanceOf(IOException.class)
                .hasMessage("Invalid AI plain-language summary.");
    }

    @Test
    void requirementWithoutDatabaseImpactAcceptsNotApplicableDecision() throws Exception {
        serve(exchange -> respond(exchange, 200, VALID_RESULT));
        Map<String, Object> requirementPayload = Map.of(
                "projectId", "fixture-project",
                "sourceSummary", Map.of("changeRequirement", Map.of("title", "버튼 색상 변경")),
                "databaseSummary", Map.of("tables", List.of()), "relations", List.of());

        assertThat(client(Duration.ofSeconds(3)).analyze(requirementPayload))
                .extracting(result -> ((Map<?, ?>) result.get("databaseImpact")).get("status"))
                .isEqualTo("NOT_APPLICABLE");
    }

    @Test
    void missingDatabaseImpactIsRejected() {
        serve(exchange -> respond(exchange, 200, """
                {"summary":"테스트 분석 결과","riskLevel":"LOW","riskScore":7,
                 "frameworks":[],"impactAreas":[],"recommendations":[],"evidences":[]}
                """));

        assertThatThrownBy(() -> client(Duration.ofSeconds(3)).analyze(PAYLOAD))
                .isInstanceOf(IOException.class)
                .hasMessage("Invalid AI database impact contract.");
    }

    @Test
    void malformedJsonDoesNotLeakParserBodyFragments() {
        serve(exchange -> respond(exchange, 200, "{ remote-body-secret-token: broken-json }"));

        assertThatThrownBy(() -> client(Duration.ofSeconds(3)).analyze(PAYLOAD))
                .isInstanceOf(IOException.class)
                .hasMessage("Invalid AI result JSON.")
                .hasNoCause();
    }

    @Test
    void jsonNullIsRejectedAsInvalidContract() {
        serve(exchange -> respond(exchange, 200, "null"));

        assertThatThrownBy(() -> client(Duration.ofSeconds(3)).analyze(PAYLOAD))
                .isInstanceOf(IOException.class)
                .hasMessage("Invalid AI result contract.")
                .hasNoCause();
    }

    @Test
    void oversizedChunkedBodyIsRejectedByByteCountBeforeJsonParsing() {
        // Fewer than one million characters, but more than one million UTF-8 bytes.
        byte[] oversized = "한".repeat(AiAnalysisClient.MAX_RESPONSE_BYTES / 3 + 1)
                .getBytes(StandardCharsets.UTF_8);
        serve(exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(oversized);
            }
        });

        assertThatThrownBy(() -> client(Duration.ofSeconds(3)).analyze(PAYLOAD))
                .isInstanceOf(IOException.class)
                .hasMessage("AI response exceeds size limit.")
                .hasNoCause();
    }

    @Test
    void deadlineCoversBodyThatStallsAfterHeaders() {
        CountDownLatch headersSent = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        serve(stalledBody(headersSent, release));
        AiAnalysisClient client = client(Duration.ofMillis(500));

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                    assertThatThrownBy(() -> client.analyze(PAYLOAD))
                            .isInstanceOf(IOException.class)
                            .hasMessage("AI analysis request timed out.")
                            .hasNoCause());
            assertThat(headersSent.getCount()).as("Server sent headers before the deadline").isZero();
        } finally {
            release.countDown();
        }
    }

    @Test
    void interruptedCallerCancelsTheInFlightExchange() throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch callerFinished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        serve(stalledBody(headersSent, release));
        AiAnalysisClient client = client(Duration.ofSeconds(10));
        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                client.analyze(PAYLOAD);
            } catch (Throwable exception) {
                failure.set(exception);
            } finally {
                callerFinished.countDown();
            }
        });

        try {
            assertThat(headersSent.await(3, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            assertThat(callerFinished.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isInstanceOf(InterruptedException.class);
        } finally {
            caller.interrupt();
            release.countDown();
        }
    }

    @Test
    void streamsAiServerPhaseBeforeTheFinalValidatedResult() throws Exception {
        CountDownLatch progressReceived = new CountDownLatch(1);
        CountDownLatch releaseResult = new CountDownLatch(1);
        AtomicReference<Map<String, Object>> finalResult = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var events = new CopyOnWriteArrayList<AiAnalysisClient.ProgressEvent>();
        server.createContext("/api/analysis/project/stream", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("{\"type\":\"progress\",\"code\":\"GENERAL_STARTED\"}\n"
                        .getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                try { releaseResult.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                String compact = JsonMapper.builder().build().writeValueAsString(
                        JsonMapper.builder().build().readTree(VALID_RESULT));
                exchange.getResponseBody().write(("{\"type\":\"progress\",\"code\":\"GENERAL_COMPLETED\"}\n"
                        + "{\"type\":\"result\",\"result\":" + compact + "}\n").getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                finalResult.set(client(Duration.ofSeconds(5)).analyzeWithProgress(PAYLOAD, event -> {
                    events.add(event);
                    progressReceived.countDown();
                }));
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });
        try {
            assertThat(progressReceived.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(finalResult.get()).isNull();
            releaseResult.countDown();
            caller.join(3000);
            assertThat(failure.get()).isNull();
            assertThat(finalResult.get()).containsEntry("summary", "테스트 분석 결과");
            assertThat(events).extracting(AiAnalysisClient.ProgressEvent::code)
                    .containsExactly("GENERAL_STARTED", "GENERAL_COMPLETED");
        } finally {
            releaseResult.countDown();
            caller.interrupt();
        }
    }

    @Test
    void streamRejectsUnknownProgressWithoutExposingRemoteText() {
        server.createContext("/api/analysis/project/stream", exchange -> respond(exchange, 200,
                "{\"type\":\"progress\",\"code\":\"remote-body-secret-token\"}\n"));
        server.start();
        assertThatThrownBy(() -> client(Duration.ofSeconds(3)).analyzeWithProgress(PAYLOAD, event -> {}))
                .isInstanceOf(IOException.class).hasMessage("Invalid AI progress event.")
                .hasNoCause();
    }

    @Test
    void streamCarriesSafeModelOutputLimitCodeWithoutRemoteMessage() {
        server.createContext("/api/analysis/project/stream", exchange -> respond(exchange, 200,
                "{\"type\":\"error\",\"code\":\"MODEL_OUTPUT_LIMIT\",\"message\":\"remote-private-text\"}\n"));
        server.start();
        assertThatThrownBy(() -> client(Duration.ofSeconds(3)).analyzeWithProgress(PAYLOAD, event -> {}))
                .isInstanceOfSatisfying(AiAnalysisClient.ModelFailureException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("MODEL_OUTPUT_LIMIT");
                    assertThat(exception.getMessage()).contains("출력 한도").doesNotContain("remote-private-text");
                }).hasNoCause();
    }

    @Test
    void unknownModelErrorCodeIsNotTrustedOrExposed() {
        var exception = new AiAnalysisClient.ModelFailureException("remote-private-text");
        assertThat(exception.code()).isEqualTo("ANALYSIS_FAILED");
        assertThat(exception.getMessage()).doesNotContain("remote-private-text");
    }

    @Test
    void streamAcceptsNumberedDatabaseBatchAndCheckpointEvents() throws Exception {
        server.createContext("/api/analysis/project/stream", exchange -> respond(exchange, 200,
                "{\"type\":\"progress\",\"code\":\"GENERAL_REUSED\"}\n"
                + "{\"type\":\"progress\",\"code\":\"DATABASE_BATCH_STARTED\",\"attempt\":25}\n"
                + "{\"type\":\"progress\",\"code\":\"DATABASE_BATCH_REUSED\",\"attempt\":25}\n"
                + "{\"type\":\"progress\",\"code\":\"DATABASE_MERGE_COMPLETED\",\"attempt\":2}\n"
                + "{\"type\":\"progress\",\"code\":\"RECONCILIATION_STARTED\"}\n"
                + "{\"type\":\"progress\",\"code\":\"RECONCILIATION_REUSED\"}\n"
                + "{\"type\":\"progress\",\"code\":\"RECONCILIATION_COMPLETED\"}\n"
                + "{\"type\":\"progress\",\"code\":\"DATABASE_SCOPE_FOCUSED\",\"attempt\":26}\n"
                + "{\"type\":\"progress\",\"code\":\"DATABASE_SCOPE_FULL\",\"attempt\":40}\n"
                + "{\"type\":\"result\",\"result\":" + VALID_RESULT.replace("\n", "") + "}\n"));
        server.start();
        var events = new CopyOnWriteArrayList<AiAnalysisClient.ProgressEvent>();
        assertThat(client(Duration.ofSeconds(3)).analyzeWithProgress(PAYLOAD, events::add)).containsKey("summary");
        assertThat(events).extracting(AiAnalysisClient.ProgressEvent::code)
                .containsExactly("GENERAL_REUSED", "DATABASE_BATCH_STARTED", "DATABASE_BATCH_REUSED", "DATABASE_MERGE_COMPLETED",
                        "RECONCILIATION_STARTED", "RECONCILIATION_REUSED", "RECONCILIATION_COMPLETED", "DATABASE_SCOPE_FOCUSED", "DATABASE_SCOPE_FULL");
        assertThat(events.get(1).attempt()).isEqualTo(25);
    }

    @Test
    void streamUnauthorizedNeverFallsBackOrExposesRemoteBody() {
        server.createContext("/api/analysis/project/stream", exchange ->
                respond(exchange, 401, "remote-body-secret-token"));
        server.createContext("/api/analysis/project", exchange -> respond(exchange, 200, VALID_RESULT));
        server.start();
        assertThatThrownBy(() -> client(Duration.ofSeconds(3)).analyzeWithProgress(PAYLOAD, event -> {}))
                .isInstanceOf(IOException.class)
                .hasMessage("AI 분석/추론 서버가 분석 요청을 거부했습니다(HTTP 401).")
                .hasNoCause();
    }

    @Test
    void oldAiServerWithoutStreamFallsBackToTheExistingAnalysisApi() throws Exception {
        server.createContext("/api/analysis/project/stream", exchange -> respond(exchange, 404, "not found"));
        server.createContext("/api/analysis/project", exchange -> respond(exchange, 200, VALID_RESULT));
        server.start();
        assertThat(client(Duration.ofSeconds(3)).analyzeWithProgress(PAYLOAD, event -> {}))
                .containsEntry("summary", "테스트 분석 결과");
    }

    @Test
    void streamDeadlineAlsoCoversAStalledBody() {
        CountDownLatch headersSent = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/api/analysis/project/stream", stalledBody(headersSent, release));
        server.start();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                    assertThatThrownBy(() -> client(Duration.ofMillis(500))
                            .analyzeWithProgress(PAYLOAD, event -> {}))
                            .isInstanceOf(IOException.class)
                            .hasMessage("AI analysis request timed out.")
                            .hasNoCause());
            assertThat(headersSent.getCount()).isZero();
        } finally {
            release.countDown();
        }
    }

    private AiAnalysisClient client(Duration timeout) {
        return new AiAnalysisClient(JsonMapper.builder().build(),
                "http://127.0.0.1:" + server.getAddress().getPort(), API_KEY, timeout);
    }

    private void serve(HttpHandler handler) {
        server.createContext("/api/analysis/project", handler);
        server.start();
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private HttpHandler stalledBody(CountDownLatch headersSent, CountDownLatch release) {
        return exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                headersSent.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        };
    }
}
