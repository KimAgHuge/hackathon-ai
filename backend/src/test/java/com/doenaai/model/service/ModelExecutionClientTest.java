package com.doenaai.model.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** 실제 서버 대신 루프백 응답으로 인증·서명·응답 제한과 안전한 오류를 확인한다. */
class ModelExecutionClientTest {
    private static final String INTERNAL_KEY = "synthetic-internal-model-key-at-least-32-characters";
    private static final String TRANSFER_KEY = "synthetic-training-transfer-key-at-least-32-characters";
    private static final int RESPONSE_LIMIT = 262144;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private HttpServer server;
    private ExecutorService handlers;

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

    private String address() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private ModelExecutionClient client() {
        return new ModelExecutionClient(mapper, address(), INTERNAL_KEY, address(), TRANSFER_KEY, "test-instance");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } finally {
            exchange.close();
        }
    }

    private record Captured(String method, String path, String timestamp, String signature, byte[] body, String internalKey) { }

    private static String signature(Captured request) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TRANSFER_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((request.timestamp() + "\n" + request.method() + "\n" + request.path() + "\n").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(mac.doFinal(request.body()));
    }

    @Test
    void signsTrainingTimestampMethodPathAndExactUtf8Body() throws Exception {
        var requests = new ArrayList<Captured>();
        server.createContext("/api/training", exchange -> {
            synchronized (requests) {
                requests.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                        exchange.getRequestHeaders().getFirst("X-Transfer-Timestamp"),
                        exchange.getRequestHeaders().getFirst("X-Transfer-Signature"),
                        exchange.getRequestBody().readAllBytes(), exchange.getRequestHeaders().getFirst("X-Internal-Api-Key")));
            }
            respond(exchange, 200, "{\"status\":\"PENDING\"}");
        });
        server.start();
        var body = new LinkedHashMap<String, Object>();
        body.put("iterations", 10);
        body.put("label", "학습 시험");
        long before = Instant.now().getEpochSecond();
        client().training("POST", "/api/training/jobs", body);
        client().training("GET", "/api/training/capabilities", Map.of());
        long after = Instant.now().getEpochSecond();

        List<Captured> captured;
        synchronized (requests) { captured = List.copyOf(requests); }
        assertThat(captured).hasSize(2);
        assertThat(captured.getFirst().body()).isEqualTo(mapper.writeValueAsBytes(body));
        assertThat(new String(captured.getFirst().body(), StandardCharsets.UTF_8)).contains("학습 시험");
        assertThat(captured.getLast().body()).isEmpty();
        assertThat(captured.stream().map(Captured::method).toList()).containsExactly("POST", "GET");
        assertThat(captured.stream().map(Captured::path).toList()).containsExactly("/api/training/jobs", "/api/training/capabilities");
        for (var request : captured) {
            assertThat(Long.parseLong(request.timestamp())).isBetween(before, after);
            assertThat(request.signature()).isEqualTo(signature(request)).matches("[0-9a-f]{64}");
            assertThat(request.internalKey()).isNull();
        }
    }

    @Test
    void sendsOnlyInternalKeyToInferenceWithoutTrainingCredentialsOrUpgrade() {
        var internal = new AtomicReference<String>();
        var transfer = new AtomicReference<String>();
        var timestamp = new AtomicReference<String>();
        var upgrade = new AtomicReference<String>();
        var method = new AtomicReference<String>();
        server.createContext("/api/models/installed", exchange -> {
            internal.set(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"));
            transfer.set(exchange.getRequestHeaders().getFirst("X-Transfer-Signature"));
            timestamp.set(exchange.getRequestHeaders().getFirst("X-Transfer-Timestamp"));
            upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            method.set(exchange.getRequestMethod());
            respond(exchange, 200, "{\"model\":\"local-test:latest\",\"currentRevision\":0,\"models\":[]}");
        });
        server.start();
        assertThat(client().inference("GET", "/api/models/installed", Map.of(), false)).containsEntry("model", "local-test:latest");
        assertThat(internal.get()).isEqualTo(INTERNAL_KEY);
        assertThat(transfer.get()).isNull();
        assertThat(timestamp.get()).isNull();
        assertThat(upgrade.get()).isNull();
        assertThat(method.get()).isEqualTo("GET");
    }

    @Test
    void refusesCrossOriginRedirectWithoutForwardingEitherCredential() throws IOException {
        var target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.setExecutor(handlers);
        var received = new AtomicInteger();
        target.createContext("/redirect-target", exchange -> {
            received.incrementAndGet();
            respond(exchange, 200, "{}");
        });
        target.start();
        try {
            server.createContext("/redirect", exchange -> {
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + target.getAddress().getPort() + "/redirect-target");
                respond(exchange, 302, "{\"private\":\"redirect-body\"}");
            });
            server.start();
            var inference = catchThrowableOfType(() -> client().inference("GET", "/redirect", Map.of(), false), ModelExecutionClient.RemoteFailure.class);
            var training = catchThrowableOfType(() -> client().training("GET", "/redirect", Map.of()), ModelExecutionClient.RemoteFailure.class);
            assertThat(inference.status).isEqualTo(302);
            assertThat(training.status).isEqualTo(302);
            assertThat(inference.uncertain).isFalse();
            assertThat(training.uncertain).isFalse();
            assertThat(received.get()).isZero();
        } finally {
            target.stop(0);
        }
    }

    @Test
    void acceptsExactlyBoundedJsonAndRejectsOneByteBeyondTheLimit() {
        String prefix = "{\"value\":\"", suffix = "\"}";
        int contentLength = RESPONSE_LIMIT - prefix.length() - suffix.length();
        var body = new AtomicReference<>(prefix + "x".repeat(contentLength) + suffix);
        server.createContext("/bounded", exchange -> respond(exchange, 200, body.get()));
        server.start();
        var result = client().inference("GET", "/bounded", Map.of(), false);
        assertThat(((String) result.get("value")).length()).isEqualTo(contentLength);
        body.set(prefix + "x".repeat(contentLength + 1) + suffix);
        var failure = catchThrowableOfType(() -> client().inference("GET", "/bounded", Map.of(), false), ModelExecutionClient.RemoteFailure.class);
        assertThat(failure.uncertain).isTrue();
        assertThat(failure.status).isEqualTo(503);
        assertThat(failure.getMessage()).doesNotContain(body.get());
    }

    @Test
    void exposesOnlySafeFailureCodesAndNeverRemoteSecretsOrServerAddresses() {
        String privateValue = "synthetic-private-response-value";
        server.createContext("/rejected", exchange -> respond(exchange, 503,
                "{\"detail\":{\"code\":\"MODEL_UNAVAILABLE\",\"message\":\"" + privateValue + " " + INTERNAL_KEY
                        + " " + address() + "\"},\"password\":\"" + TRANSFER_KEY + "\"}"));
        server.start();
        var failure = catchThrowableOfType(() -> client().inference("POST", "/rejected", Map.of("model", "local-test:latest"), false),
                ModelExecutionClient.RemoteFailure.class);
        assertThat(failure.code).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(failure.status).isEqualTo(503);
        assertThat(failure.uncertain).isTrue();
        assertThat(failure.getMessage()).doesNotContain(privateValue, INTERNAL_KEY, TRANSFER_KEY, address());
        assertThat(failure.getCause()).isNull();

        for (String unsafe : List.of("http://user:synthetic-password@127.0.0.1", address() + "?token=synthetic-private", address() + "#private")) {
            var configured = new ModelExecutionClient(mapper, unsafe, INTERNAL_KEY, address(), TRANSFER_KEY, "test-instance");
            var invalid = catchThrowableOfType(() -> configured.inference("GET", "/api/models/installed", Map.of(), false),
                    ModelExecutionClient.RemoteFailure.class);
            assertThat(invalid.code).isEqualTo("UNCONFIGURED");
            assertThat(invalid.uncertain).isFalse();
            assertThat(invalid.getMessage()).doesNotContain(unsafe, "synthetic-password", "synthetic-private");
        }
    }

    @Test
    void targetKeysAreStableForTheSameInstanceButDistinguishAddressesAndRoles() {
        var first = new ModelExecutionClient(mapper, "http://127.0.0.1:18000", INTERNAL_KEY,
                "http://127.0.0.1:18011", TRANSFER_KEY, "same-instance");
        var same = new ModelExecutionClient(mapper, "http://127.0.0.1:18000/", "synthetic-rotated-key-at-least-32-characters",
                "http://127.0.0.1:18011/", TRANSFER_KEY, "same-instance");
        var otherAddress = new ModelExecutionClient(mapper, "http://127.0.0.1:18001", INTERNAL_KEY,
                "http://127.0.0.1:18012", TRANSFER_KEY, "same-instance");
        var otherInstance = new ModelExecutionClient(mapper, "http://127.0.0.1:18000", INTERNAL_KEY,
                "http://127.0.0.1:18011", TRANSFER_KEY, "other-instance");
        assertThat(first.targetKey(false)).matches("[0-9a-f]{64}").isEqualTo(same.targetKey(false))
                .isNotEqualTo(otherAddress.targetKey(false)).isNotEqualTo(otherInstance.targetKey(false));
        assertThat(first.targetKey(true)).isEqualTo(same.targetKey(true)).isNotEqualTo(otherAddress.targetKey(true));
        assertThat(first.targetKey(false)).isNotEqualTo(first.targetKey(true));
        assertThat(first.targetKey(false)).doesNotContain("127.0.0.1", INTERNAL_KEY, TRANSFER_KEY, "same-instance");
    }
}
