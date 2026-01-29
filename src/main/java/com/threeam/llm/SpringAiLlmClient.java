package com.threeam.llm;

import io.micrometer.core.instrument.Metrics;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;

// Spring AI ChatModel 위임 구현. 요청 조립, 인증, 503 재시도는 프레임워크와 설정(GoogleGenAiLlmConfig)이 맡고,
// 여기서는 셋만 책임진다: (1) 우리 프롬프트 계약 유지 — 여러 SYSTEM 조각 병합,
// (2) 논블로킹 계약 유지 — 블로킹 call()을 전용 풀에 격리해 CompletableFuture로,
// (3) 역할별 호출 옵션 — 채팅/추출은 thinking 최소화, 진단은 전용 모델 + temperature 0.
@Slf4j
public class SpringAiLlmClient implements LlmClient {

    private final ChatModel chatModel;
    private final LlmProperties properties;
    private final Executor executor;

    public SpringAiLlmClient(ChatModel chatModel, LlmProperties properties, Executor executor) {
        this.chatModel = chatModel;
        this.properties = properties;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<String> generate(List<ChatMessage> messages) {
        return call(messages, defaultOptions().build(), false);
    }

    @Override
    public CompletableFuture<String> generateJson(List<ChatMessage> messages) {
        // JSON 모드: 모델이 코드펜스, 잡설 없이 순수 JSON만 뱉도록 강제한다.
        return call(messages, defaultOptions().responseMimeType("application/json").build(), false);
    }

    @Override
    public CompletableFuture<String> generateJsonDeep(List<ChatMessage> messages) {
        // 정밀 판단(진단): 전용 모델(비우면 기본), temperature 0(같은 사실 위 점수 출렁임 실측 대응),
        // thinking은 기본 유지(긴 루브릭 추론에 필요).
        GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder()
                .model(properties.assessmentModelOrDefault())
                .temperature(0.0)
                .responseMimeType("application/json")
                .build();
        return call(messages, options, true);
    }

    // 채팅/추출 공통: thinking 최소화 — thinking 토큰이 출력 과금의 90%(로그 실측)인데
    // 짧은 응답 품질 기여는 작다. Gemini 3.x는 완전 끄기가 없어 minimal로 낮춘다.
    private GoogleGenAiChatOptions.Builder defaultOptions() {
        return GoogleGenAiChatOptions.builder()
                .model(properties.getModel())
                .thinkingLevel(GoogleGenAiThinkingLevel.MINIMAL);
    }

    private CompletableFuture<String> call(List<ChatMessage> messages, GoogleGenAiChatOptions options, boolean deep) {
        Prompt prompt = new Prompt(toSpringAiMessages(messages), options);
        // 타임아웃 없이는 LLM이 매달릴 때 future가 영원히 미완 → 답도 폴백도 저장되지 않는다.
        // 정밀 판단(deep)은 추론이 긴 모델을 쓸 수 있어 여유를 두 배 준다.
        long timeout = deep ? properties.getTimeoutSeconds() * 2 : properties.getTimeoutSeconds();
        return CompletableFuture.supplyAsync(() -> callBlocking(prompt), executor)
                .orTimeout(timeout, TimeUnit.SECONDS)
                // 호출 성공/실패 카운터 — 실패율, 호출량을 /actuator/prometheus로 관측(비용, 장애 감지).
                .handle((text, ex) -> {
                    Metrics.counter("llm.calls",
                            "provider", properties.getProvider(), "result", ex == null ? "success" : "error").increment();
                    if (ex == null) {
                        return text;
                    }
                    throw asLlmException(ex);
                });
    }

    private String callBlocking(Prompt prompt) {
        try {
            ChatResponse response = chatModel.call(prompt);
            String text = response == null || response.getResult() == null
                    ? null : response.getResult().getOutput().getText();
            if (text == null || text.isBlank()) {
                log.error("{} 응답에 텍스트가 없음", properties.getProvider());
                throw new LlmException();
            }
            logUsage(response);
            return text;
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            // 상태코드, 오류 메타는 SDK 예외 메시지에 담겨 온다. 도메인 예외로 감싸 전역 핸들러가 502로 응답하게.
            log.error("{} 호출 실패", properties.getProvider(), e);
            throw new LlmException();
        }
    }

    // future 경계(orTimeout, supplyAsync)를 지나며 감싸인 예외를 도메인 예외 하나로 정규화한다.
    private LlmException asLlmException(Throwable ex) {
        Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
        if (cause instanceof LlmException llm) {
            return llm;
        }
        if (cause instanceof TimeoutException) {
            log.error("{} 응답 대기 초과({}초 상한)", properties.getProvider(), properties.getTimeoutSeconds());
        } else {
            log.error("{} 호출 실패(비정상 완료)", properties.getProvider(), cause);
        }
        return new LlmException();
    }

    // Google 경로의 system_instruction은 한 자리라, 프롬프트에 흩어 담긴 SYSTEM 조각들
    // (페르소나, 원장, 요약, 매 턴 리마인더)을 순서대로 한 덩어리로 병합한다.
    // Spring AI GenAI 모듈은 첫 SystemMessage만 싣고 나머지를 조용히 버리기 때문에 병합 없이는 프롬프트가 잘린다.
    private List<Message> toSpringAiMessages(List<ChatMessage> messages) {
        StringBuilder system = new StringBuilder();
        List<Message> conversation = new ArrayList<>();
        for (ChatMessage message : messages) {
            switch (message.role()) {
                case SYSTEM -> system.append(message.content()).append('\n');
                case USER -> conversation.add(new UserMessage(message.content()));
                case ASSISTANT -> conversation.add(new AssistantMessage(message.content()));
            }
        }
        List<Message> result = new ArrayList<>();
        if (system.length() > 0) {
            result.add(new SystemMessage(system.toString().trim()));
        }
        result.addAll(conversation);
        return result;
    }

    // 호출당 실제 토큰량을 남긴다 — 비용 검증(프롬프트 창 크기, 추출 호출 비용)은 추정이 아니라 이 실측으로 한다.
    private void logUsage(ChatResponse response) {
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage == null) {
            return;
        }
        log.info("{} 토큰 사용: input={}, output={}, total={}",
                properties.getProvider(), usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
        // 토큰 총량 분포 — 프롬프트 창, 추출 호출이 비용에 미치는 영향을 실측으로 집계한다.
        Metrics.summary("llm.tokens.total", "provider", properties.getProvider()).record(usage.getTotalTokens());
    }
}
