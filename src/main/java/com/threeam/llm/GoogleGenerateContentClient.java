package com.threeam.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

// Google generateContent 계열 공통 구현. AI Studio(API 키)와 Vertex AI(서비스 계정)는
// 요청 본문과 응답 구조가 같고 엔드포인트와 인증만 달라서, 그 둘만 구현체에 맡긴다.
@Slf4j
abstract class GoogleGenerateContentClient implements LlmClient {

    // 연결 수립 대기 상한. 응답 대기(timeoutSeconds)와 별개로, 네트워크 단절을 빨리 알아챈다.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    // 503(혼잡) 재시도 대기. 폴링 여유(45초) 안에 "즉시 실패 + 대기 + 재시도(최대 30초)"가 들어간다.
    private static final long RETRY_DELAY_SECONDS = 2;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    protected GoogleGenerateContentClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    abstract String endpoint();

    // 요청마다 호출된다 — 만료되는 토큰 방식(Vertex)도 여기서 최신 값을 실을 수 있게.
    abstract void authorize(HttpRequest.Builder builder);

    abstract long timeoutSeconds();

    // 로그 라벨용
    abstract String providerName();

    @Override
    public CompletableFuture<String> generate(List<ChatMessage> messages) {
        return send(buildRequest(messages, false));
    }

    @Override
    public CompletableFuture<String> generateJson(List<ChatMessage> messages) {
        return send(buildRequest(messages, true));
    }

    private CompletableFuture<String> send(HttpRequest request) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenCompose(this::retryOnceIfOverloaded)
                .thenApply(this::extractText);
    }

    // 503은 피크 시간대의 일상이라(실측) 짧게 기다렸다 1회 재시도한다.
    // 429(한도 소진)나 4xx는 다시 보내도 같은 결과라 재시도하지 않는다.
    private CompletableFuture<HttpResponse<String>> retryOnceIfOverloaded(HttpResponse<String> response) {
        if (response.statusCode() != 503) {
            return CompletableFuture.completedFuture(response);
        }
        log.warn("{} 503(혼잡) — {}초 뒤 1회 재시도", providerName(), RETRY_DELAY_SECONDS);
        return CompletableFuture.supplyAsync(() -> null,
                        CompletableFuture.delayedExecutor(RETRY_DELAY_SECONDS, TimeUnit.SECONDS))
                .thenCompose(ignored -> httpClient.sendAsync(
                        response.request(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
    }

    private HttpRequest buildRequest(List<ChatMessage> messages, boolean json) {
        // system은 system_instruction으로, 대화는 contents(user/model)로 나눠 받는다.
        StringBuilder system = new StringBuilder();
        List<Map<String, Object>> contents = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message.role() == LlmRole.SYSTEM) {
                system.append(message.content()).append('\n');
                continue;
            }
            String role = message.role() == LlmRole.USER ? "user" : "model";
            contents.add(Map.of("role", role, "parts", List.of(Map.of("text", message.content()))));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        if (system.length() > 0) {
            body.put("system_instruction", Map.of("parts", List.of(Map.of("text", system.toString().trim()))));
        }
        body.put("contents", contents);
        // JSON 모드: 모델이 코드펜스, 잡설 없이 순수 JSON만 뱉도록 강제한다.
        if (json) {
            body.put("generationConfig", Map.of("responseMimeType", "application/json"));
        }

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint()))
                    .header("Content-Type", "application/json")
                    // 타임아웃 없이는 LLM이 매달릴 때 future가 영원히 미완 → 답도 폴백도 저장되지 않는다.
                    .timeout(Duration.ofSeconds(timeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
            authorize(builder);
            return builder.build();
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            log.error("{} 요청 조립 실패", providerName(), e);
            throw new LlmException();
        }
    }

    private String extractText(HttpResponse<String> response) {
        if (response.statusCode() / 100 != 2) {
            log.error("{} 응답 오류: status={} body={}", providerName(), response.statusCode(), response.body());
            throw new LlmException();
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            logUsage(root);
            JsonNode text = root
                    .path("candidates").path(0).path("content").path("parts").path(0).path("text");
            if (text.isMissingNode()) {
                log.error("{} 응답에 텍스트가 없음: {}", providerName(), response.body());
                throw new LlmException();
            }
            return text.asText();
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            log.error("{} 응답 파싱 실패", providerName(), e);
            throw new LlmException();
        }
    }

    // 호출당 실제 토큰량을 남긴다 — 비용 검증(프롬프트 창 크기, 추출 호출 비용)은 추정이 아니라 이 실측으로 한다.
    private void logUsage(JsonNode root) {
        JsonNode usage = root.path("usageMetadata");
        if (usage.isMissingNode()) {
            return;
        }
        log.info("{} 토큰 사용: input={}, output={}, total={}",
                providerName(),
                usage.path("promptTokenCount").asInt(0),
                usage.path("candidatesTokenCount").asInt(0),
                usage.path("totalTokenCount").asInt(0));
    }
}
