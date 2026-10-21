package com.doenaai.model.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** 서버에 설정된 주소만 조회하며 모델 설치 여부와 실제 추론 성공을 구분한다. */
@Service
public class ModelRuntimeClient {
    private static final int MAX_BYTES = 16_384;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient client;

    @Autowired
    public ModelRuntimeClient(ObjectMapper mapper,
                              @Value("${doenaai.ai-server.base-url:http://localhost:8000}") String baseUrl,
                              @Value("${doenaai.ai-server.internal-api-key:}") String apiKey) {
        this(mapper, baseUrl, apiKey, Duration.ofSeconds(5));
    }

    ModelRuntimeClient(ObjectMapper mapper, String baseUrl, String apiKey, Duration timeout) {
        this.mapper = mapper;
        this.endpoint = endpointFor(baseUrl);
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** 내부 키로 상태만 확인한다. 로딩·학습·배포 명령은 보내지 않는다. */
    public Map<String, Object> status() {
        if (endpoint == null || apiKey == null || apiKey.length() < 32) {
            return result("UNCONFIGURED", null, false, "AI 분석/추론 서버 주소와 내부 인증 설정을 확인해 주세요.");
        }
        CompletableFuture<HttpResponse<byte[]>> exchange = null;
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("X-Internal-Api-Key", apiKey).GET().build();
            exchange = client.sendAsync(request, info -> new LimitedBody());
            var response = exchange.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) {
                String message = response.statusCode() == 401 || response.statusCode() == 403
                        ? "AI 분석/추론 서버의 내부 인증 설정을 확인해 주세요."
                        : "AI 분석/추론 서버 또는 설정 모델을 확인하지 못했습니다. 서버 상태와 모델 설치 여부를 확인해 주세요.";
                return result("UNAVAILABLE", null, false, message);
            }
            var value = mapper.readTree(response.body());
            String model = value.path("model").asText("");
            if (!"UP".equals(value.path("status").asText()) || !value.path("available").isBoolean()
                    || !value.path("available").asBoolean() || !model.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,499}")) {
                return result("UNAVAILABLE", null, false, "모델 상태 응답을 확인하지 못했습니다. 서버의 응답 형식을 확인해 주세요.");
            }
            return result("AVAILABLE", model, true,
                    "설정 모델의 설치와 서버 응답을 확인했습니다. 메모리 적재·추론 성공·학습 품질을 확인한 것은 아닙니다.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return result("UNAVAILABLE", null, false, "모델 상태 확인이 중단됐습니다. 다시 확인해 주세요.");
        } catch (Exception exception) {
            return result("UNAVAILABLE", null, false, "모델 상태를 확인하지 못했습니다. 연결 또는 응답 시간 제한을 확인해 주세요.");
        } finally {
            if (exchange != null && !exchange.isDone()) exchange.cancel(true);
        }
    }

    private static URI endpointFor(String baseUrl) {
        try {
            if (baseUrl == null) return null;
            var base = URI.create(baseUrl.strip().replaceAll("/+$", ""));
            if (!List.of("http", "https").contains(base.getScheme()) || base.getHost() == null
                    || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) return null;
            return URI.create(base + "/api/models/health");
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Map<String, Object> result(String status, String model, boolean available, String message) {
        var result = new LinkedHashMap<String, Object>();
        result.put("status", status);
        result.put("model", model);
        result.put("available", available);
        result.put("checkedAt", Instant.now().toString());
        result.put("message", message);
        return result;
    }

    /** 원격 오류 원문을 노출하지 않고 작은 상태 응답만 메모리에 받는다. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }
        @Override public void onNext(List<ByteBuffer> chunks) {
            for (var chunk : chunks) {
                if (bytes.size() + chunk.remaining() > MAX_BYTES) {
                    subscription.cancel();
                    body.completeExceptionally(new IllegalStateException("모델 상태 응답이 크기 제한을 초과했습니다."));
                    return;
                }
                byte[] part = new byte[chunk.remaining()];
                chunk.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
