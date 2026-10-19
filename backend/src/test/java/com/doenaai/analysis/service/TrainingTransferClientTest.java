package com.doenaai.analysis.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrainingTransferClientTest {
    private static final String KEY = "synthetic-internal-key-for-local-test";
    private HttpServer server;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void postsReviewCaseWithInternalKeyAndReadsStatus() throws Exception {
        var receivedKey = new AtomicReference<String>();
        var receivedBody = new AtomicReference<String>();
        server.createContext("/api/training/transfers", exchange -> {
            receivedKey.set(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"));
            if ("POST".equals(exchange.getRequestMethod())) {
                receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            var answer = "{\"status\":\"WAITING_FOR_SYNC\",\"sha256\":\"abc\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        var client = client(KEY);
        var jobId = UUID.randomUUID();

        assertThat(client.publish(jobId, Map.of("caseId", "analysis-" + jobId)))
                .containsEntry("status", "WAITING_FOR_SYNC");
        assertThat(client.status(jobId)).containsEntry("status", "WAITING_FOR_SYNC");
        assertThat(receivedKey.get()).isEqualTo(KEY);
        assertThat(receivedBody.get()).contains(jobId.toString(), "case_draft", "caseId");
    }

    @Test
    void remoteValidationBodyIsNotExposed() throws Exception {
        server.createContext("/api/training/transfers", exchange -> {
            var answer = "private-customer-content".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(422, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        assertThatThrownBy(() -> client(KEY).publish(UUID.randomUUID(), Map.of("caseId", "synthetic")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageNotContaining("private-customer-content");
    }

    private TrainingTransferClient client(String key) {
        return new TrainingTransferClient(JsonMapper.builder().build(),
                "http://127.0.0.1:" + server.getAddress().getPort(), key);
    }

    @Test void keepsOnlyKnownValidationCodeAndNeverRemoteMessage() throws Exception {
        server.createContext("/api/training/transfers", exchange -> {
            var answer = "{\"detail\":{\"code\":\"GROUNDING_MISMATCH\",\"field\":\"answers.general\",\"message\":\"private-customer-value\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(422, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        assertThatThrownBy(() -> client(KEY).validateCase(UUID.randomUUID(), Map.of("caseId", "sample")))
                .isInstanceOfSatisfying(TrainingTransferClient.CaseValidationException.class, error -> {
                    assertThat(error.code).isEqualTo("GROUNDING_MISMATCH");
                    assertThat(error.field).isEqualTo("answers.general");
                    assertThat(error.getReason()).doesNotContain("private-customer-value");
                });
    }
}
