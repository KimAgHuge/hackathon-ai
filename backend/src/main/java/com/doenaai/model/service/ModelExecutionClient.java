package com.doenaai.model.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** 설정된 두 서버에만 연결한다. 브라우저 입력으로 주소·셸 명령·파일 경로를 바꾸지 않는다. */
@Service
public class ModelExecutionClient {
    private final ObjectMapper mapper;
    private final URI inference;
    private final URI training;
    private final String internalKey;
    private final String transferKey;
    private final String instance;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(3)).build();

    @Autowired
    public ModelExecutionClient(ObjectMapper mapper,
            @Value("${doenaai.ai-server.base-url:http://localhost:8000}") String inference,
            @Value("${doenaai.ai-server.internal-api-key:}") String internalKey,
            @Value("${doenaai.training-receiver.base-url:}") String training,
            @Value("${doenaai.training-receiver.transfer-key:}") String transferKey,
            @Value("${doenaai.model-management.instance-id:}") String instance) {
        this.mapper = mapper;
        this.inference = endpoint(inference);
        this.training = endpoint(training);
        this.internalKey = internalKey;
        this.transferKey = transferKey;
        this.instance = instance == null || instance.isBlank() ? localInstance() : instance;
    }

    public String targetKey(boolean learning) {
        try {
            String address = String.valueOf(learning ? training : inference);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((instance + "\n" + address).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException("서버 식별자를 만들지 못했습니다."); }
    }

    public Map<String, Object> inference(String method, String path, Map<String, Object> body, boolean probe) {
        return exchange(false, method, path, body, Duration.ofSeconds(probe ? 130 : path.startsWith("/api/models/operations/") ? 3 : 15));
    }

    public Map<String, Object> training(String method, String path, Map<String, Object> body) {
        return exchange(true, method, path, body, Duration.ofSeconds("GET".equals(method) ? 5 : 10));
    }

    private Map<String, Object> exchange(boolean learning, String method, String path, Map<String, Object> body, Duration timeout) {
        URI base = learning ? training : inference;
        String key = learning ? transferKey : internalKey;
        if (base == null || key == null || key.length() < 32) throw new RemoteFailure("UNCONFIGURED", false, 503);
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            byte[] bytes = "GET".equals(method) ? new byte[0] : mapper.writeValueAsBytes(body);
            var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout)
                    .header("Content-Type", "application/json");
            if (learning) {
                String timestamp = Long.toString(Instant.now().getEpochSecond());
                var mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                mac.update((timestamp + "\n" + method + "\n" + path + "\n").getBytes(StandardCharsets.UTF_8));
                builder.header("X-Transfer-Timestamp", timestamp)
                        .header("X-Transfer-Signature", HexFormat.of().formatHex(mac.doFinal(bytes)));
            } else builder.header("X-Internal-Api-Key", key);
            builder.method(method, "GET".equals(method) ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(bytes));
            pending = client.sendAsync(builder.build(), ignored -> new BoundedBody());
            var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String code = "REMOTE_REJECTED";
                try {
                    var value = mapper.readTree(response.body());
                    String supplied = value.path("detail").path("code").asText(value.path("code").asText(""));
                    if (supplied.matches("[A-Z][A-Z0-9_]{0,59}")) code = supplied;
                } catch (Exception ignored) { /* 원격 오류 원문은 보관하지 않는다. */ }
                throw new RemoteFailure(code, response.statusCode() >= 500, response.statusCode());
            }
            return mapper.readValue(response.body(), new TypeReference<>() { });
        } catch (RemoteFailure failure) { throw failure; }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RemoteFailure("REMOTE_INTERRUPTED", true, 503);
        } catch (Exception exception) {
            throw new RemoteFailure("REMOTE_UNREACHABLE", true, 503);
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    private static URI endpoint(String value) {
        try {
            URI uri = URI.create(value.strip().replaceAll("/+$", ""));
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) return null;
            return uri;
        } catch (Exception ignored) { return null; }
    }

    private static String localInstance() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (Exception ignored) { return "local-model-manager"; }
    }

    public static final class RemoteFailure extends RuntimeException {
        public final String code;
        public final boolean uncertain;
        public final int status;
        public RemoteFailure(String code, boolean uncertain, int status) {
            super("원격 관리 요청을 확인하지 못했습니다.");
            this.code = code;
            this.uncertain = uncertain;
            this.status = status;
        }
    }

    /** 본문을 끝없이 받는 연결도 전체 시간과 크기 제한 안에서 중단한다. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> chunks) {
            for (var chunk : chunks) {
                if (bytes.size() + chunk.remaining() > 262144) {
                    subscription.cancel(); body.completeExceptionally(new IllegalStateException("응답 크기 초과")); return;
                }
                byte[] part = new byte[chunk.remaining()]; chunk.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(error); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
