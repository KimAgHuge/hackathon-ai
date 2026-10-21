package com.doenaai.model.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ModelRuntimeClientTest {
    private static final String KEY = "synthetic-model-status-key-not-for-runtime";
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

    private ModelRuntimeClient client(Duration timeout) {
        return new ModelRuntimeClient(JsonMapper.builder().build(), "http://127.0.0.1:" + server.getAddress().getPort(), KEY, timeout);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void checksOnlyAuthenticatedInstallationStatus() {
        var receivedKey = new AtomicReference<String>();
        var method = new AtomicReference<String>();
        var upgrade = new AtomicReference<String>();
        server.createContext("/api/models/health", exchange -> {
            receivedKey.set(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"));
            method.set(exchange.getRequestMethod());
            upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            respond(exchange, 200, "{\"status\":\"UP\",\"model\":\"provider/model-example:latest\",\"available\":true}");
        });
        server.start();
        var result = client(Duration.ofSeconds(2)).status();
        assertThat(result).containsEntry("status", "AVAILABLE").containsEntry("available", true)
                .containsEntry("model", "provider/model-example:latest");
        assertThat(result.get("message").toString()).contains("메모리 적재", "확인한 것은 아닙니다");
        assertThat(receivedKey.get()).isEqualTo(KEY);
        assertThat(method.get()).isEqualTo("GET");
        assertThat(upgrade.get()).isNull();
        assertThat(result).doesNotContainKey("apiKey");
    }

    @Test
    void masksRemoteAuthenticationErrorsAndRedirects() {
        server.createContext("/api/models/health", exchange -> respond(exchange, 401, "synthetic-private-remote-message"));
        server.start();
        var result = client(Duration.ofSeconds(2)).status();
        assertThat(result).containsEntry("status", "UNAVAILABLE").containsEntry("model", null);
        assertThat(result.toString()).doesNotContain("synthetic-private", KEY);
    }

    @Test
    void doesNotForwardTheKeyToRedirectTargets() {
        var received = new AtomicReference<String>();
        server.createContext("/api/models/health", exchange -> {
            exchange.getResponseHeaders().set("Location", "/redirect-target");
            respond(exchange, 302, "synthetic-redirect-body");
        });
        server.createContext("/redirect-target", exchange -> {
            received.set(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"));
            respond(exchange, 200, "{}");
        });
        server.start();
        assertThat(client(Duration.ofSeconds(2)).status()).containsEntry("status", "UNAVAILABLE");
        assertThat(received.get()).isNull();
    }

    @Test
    void treatsMissingKeyAndUnsafeAddressAsConfigurationProblems() {
        var mapper = JsonMapper.builder().build();
        assertThat(new ModelRuntimeClient(mapper, "http://localhost:8000", "").status())
                .containsEntry("status", "UNCONFIGURED");
        assertThat(new ModelRuntimeClient(mapper, "https://user:example@localhost", KEY).status())
                .containsEntry("status", "UNCONFIGURED");
        assertThat(new ModelRuntimeClient(mapper, "not a server address", KEY).status())
                .containsEntry("status", "UNCONFIGURED");
    }

    @Test
    void rejectsInvalidAndOversizedStatusBodies() {
        var body = new AtomicReference<>("{\"status\":\"UP\",\"model\":\"model-example\",\"available\":\"true\"}");
        server.createContext("/api/models/health", exchange -> respond(exchange, 200, body.get()));
        server.start();
        assertThat(client(Duration.ofSeconds(2)).status()).containsEntry("status", "UNAVAILABLE");
        body.set("x".repeat(20_000));
        assertThat(client(Duration.ofSeconds(2)).status()).containsEntry("status", "UNAVAILABLE");
    }

    @Test
    void boundsTheEntireResponseNotOnlyItsHeaders() {
        server.createContext("/api/models/health", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try { Thread.sleep(1000); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThat(client(Duration.ofMillis(100)).status()).containsEntry("status", "UNAVAILABLE"));
    }
}
