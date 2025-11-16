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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Gemini(generateContent) 실연동. java.net.http.HttpClient의 sendAsync로 논블로킹 호출한다.
// llm.provider=gemini 일 때만 빈으로 등록된다(기본은 Mock).
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "gemini")
public class GeminiLlmClient implements LlmClient {

    // 연결 수립 대기 상한. 응답 대기(timeoutSeconds)와 별개로, 네트워크 단절을 빨리 알아챈다.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GeminiLlmClient(GeminiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public CompletableFuture<String> generate(List<ChatMessage> messages) {
        return send(buildRequest(messages, false));
    }

    @Override
    public CompletableFuture<String> generateJson(List<ChatMessage> messages) {
        return send(buildRequest(messages, true));
    }

    // 503(혼잡) 재시도 대기. 폴링 여유(45초) 안에 "즉시 실패 + 대기 + 재시도(최대 30초)"가 들어간다.
    private static final long RETRY_DELAY_SECONDS = 2;

    private CompletableFuture<String> send(HttpRequest request) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenCompose(this::retryOnceIfOverloaded)
                .thenApply(this::extractText);
    }

    // 무료 티어의 503은 피크 시간대의 일상이라(실측) 짧게 기다렸다 1회 재시도한다.
    // 429(한도 소진)나 4xx는 다시 보내도 같은 결과라 재시도하지 않는다.
    private CompletableFuture<HttpResponse<String>> retryOnceIfOverloaded(HttpResponse<String> response) {
        if (response.statusCode() != 503) {
            return CompletableFuture.completedFuture(response);
        }
        log.warn("Gemini 503(혼잡) — {}초 뒤 1회 재시도", RETRY_DELAY_SECONDS);
        return CompletableFuture.supplyAsync(() -> null,
                        CompletableFuture.delayedExecutor(RETRY_DELAY_SECONDS, TimeUnit.SECONDS))
                .thenCompose(ignored -> httpClient.sendAsync(
                        response.request(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
    }

    private HttpRequest buildRequest(List<ChatMessage> messages, boolean json) {
        // Gemini는 system은 system_instruction으로, 대화는 contents(user/model)로 나눠 받는다.
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

        String url = properties.getBaseUrl() + "/models/" + properties.getModel() + ":generateContent";
        try {
            return HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    // 키를 쿼리스트링에 두면 접근 로그, 프록시에 남을 수 있어 헤더로 보낸다.
                    .header("x-goog-api-key", properties.getApiKey())
                    // 타임아웃 없이는 LLM이 매달릴 때 future가 영원히 미완 → 답도 폴백도 저장되지 않는다.
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            log.error("Gemini 요청 조립 실패", e);
            throw new LlmException();
        }
    }

    private String extractText(HttpResponse<String> response) {
        if (response.statusCode() / 100 != 2) {
            log.error("Gemini 응답 오류: status={} body={}", response.statusCode(), response.body());
            throw new LlmException();
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            logUsage(root);
            JsonNode text = root
                    .path("candidates").path(0).path("content").path("parts").path(0).path("text");
            if (text.isMissingNode()) {
                log.error("Gemini 응답에 텍스트가 없음: {}", response.body());
                throw new LlmException();
            }
            return text.asText();
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            log.error("Gemini 응답 파싱 실패", e);
            throw new LlmException();
        }
    }

    // 호출당 실제 토큰량을 남긴다 — 비용 검증(프롬프트 창 크기, 추출 호출 비용)은 추정이 아니라 이 실측으로 한다.
    private void logUsage(JsonNode root) {
        JsonNode usage = root.path("usageMetadata");
        if (usage.isMissingNode()) {
            return;
        }
        log.info("Gemini 토큰 사용: input={}, output={}, total={}",
                usage.path("promptTokenCount").asInt(0),
                usage.path("candidatesTokenCount").asInt(0),
                usage.path("totalTokenCount").asInt(0));
    }
}
