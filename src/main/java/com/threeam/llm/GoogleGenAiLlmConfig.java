package com.threeam.llm;

import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.types.HttpOptions;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// Spring AI ChatModel 빈을 직접 조립한다. 스타터 자동설정(spring.ai.google.genai.*)을 쓰지 않는 이유:
// 자동설정은 "어떤 속성이 채워졌나"로 인증 경로를 고르는데, 개발 장비엔 API 키와 GCP 설정이 둘 다
// 살아 있을 수 있다. 과금 주체가 갈리는 선택(AI Studio vs Vertex 크레딧)이라 기존 LLM_PROVIDER
// 스위치가 결정론적으로 쥐게 한다. (자동설정 자체는 yml의 spring.ai.model.chat=none으로 꺼 둔다.)
@Slf4j
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class GoogleGenAiLlmConfig {

    private static final String REAL_PROVIDER =
            "'${llm.provider:mock}' == 'gemini' or '${llm.provider:mock}' == 'vertex'";

    // 503(혼잡) 재시도 대기. 폴링 여유(45초) 안에 "즉시 실패 + 대기 + 재시도"가 들어간다.
    private static final long RETRY_DELAY_MILLIS = 2000;

    @Bean
    @ConditionalOnProperty(name = "llm.provider", havingValue = "gemini")
    public Client geminiGenAiClient(LlmProperties properties) {
        return clientBuilder(properties).apiKey(properties.getApiKey()).build();
    }

    // Vertex — 같은 Gemini를 GCP 상품 경로로(크레딧 결제). 자격증명은 SDK가 ADC 표준
    // (GOOGLE_APPLICATION_CREDENTIALS)으로 읽고 토큰 발급/갱신까지 맡는다.
    @Bean
    @ConditionalOnProperty(name = "llm.provider", havingValue = "vertex")
    public Client vertexGenAiClient(LlmProperties properties) {
        return clientBuilder(properties)
                .vertexAI(true)
                .project(properties.getProjectId())
                .location(properties.getLocation())
                .build();
    }

    private Client.Builder clientBuilder(LlmProperties properties) {
        // HTTP 타임아웃은 클라이언트 전역이라 가장 긴 호출(정밀 판단 2배) 기준으로 잡는다.
        // 호출별 상한은 SpringAiLlmClient의 orTimeout이 따로 건다.
        int timeoutMillis = (int) (properties.getTimeoutSeconds() * 2 * 1000);
        return Client.builder().httpOptions(HttpOptions.builder().timeout(timeoutMillis).build());
    }

    @Bean
    @ConditionalOnExpression(REAL_PROVIDER)
    public GoogleGenAiChatModel googleGenAiChatModel(Client genAiClient, LlmProperties properties,
                                                     ObjectProvider<ObservationRegistry> observationRegistry) {
        return GoogleGenAiChatModel.builder()
                .genAiClient(genAiClient)
                .defaultOptions(GoogleGenAiChatOptions.builder().model(properties.getModel()).build())
                .retryTemplate(overloadRetryTemplate(properties))
                // 액추에이터의 레지스트리를 물리면 gen_ai.* 표준 메트릭(지연, 토큰)이 공짜로 잡힌다.
                .observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
                .build();
    }

    // LLM 호출 전용 풀. Spring AI의 call()은 블로킹이라 톰캣 요청 스레드 대신 여기서 대기시켜
    // 기존 sendAsync의 논블로킹 계약을 유지한다. 트레이드오프: 동시 LLM 호출 수만큼 풀 스레드가 잡힌다 —
    // 풀 크기가 곧 동시 호출 상한이고, 초과분은 큐에서 대기하다 orTimeout에 걸린다(무한 적체 방지).
    @Bean
    @ConditionalOnExpression(REAL_PROVIDER)
    public ThreadPoolTaskExecutor llmExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("llm-");
        executor.setCorePoolSize(8);
        executor.setAllowCoreThreadTimeOut(true);
        return executor;
    }

    @Bean
    @ConditionalOnExpression(REAL_PROVIDER)
    public SpringAiLlmClient springAiLlmClient(GoogleGenAiChatModel googleGenAiChatModel,
                                               LlmProperties properties, ThreadPoolTaskExecutor llmExecutor) {
        return new SpringAiLlmClient(googleGenAiChatModel, properties, llmExecutor);
    }

    // 503은 피크 시간대의 일상이라(실측) 짧게 기다렸다 1회만 재시도한다.
    // 429(한도 소진)나 4xx는 다시 보내도 같은 결과라 재시도하지 않는다.
    // Spring AI 기본 RetryTemplate은 TransientAiException 분류를 전제하는데 GenAI 모듈은
    // SDK 예외를 분류 없이 RuntimeException으로 감싸 사실상 재시도가 안 걸린다 — 그래서 직접 정의한다.
    private RetryTemplate overloadRetryTemplate(LlmProperties properties) {
        RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(new SimpleRetryPolicy(2) {
            @Override
            public boolean canRetry(RetryContext context) {
                Throwable last = context.getLastThrowable();
                return super.canRetry(context) && (last == null || isOverloaded(last));
            }
        });
        FixedBackOffPolicy backOff = new FixedBackOffPolicy();
        backOff.setBackOffPeriod(RETRY_DELAY_MILLIS);
        template.setBackOffPolicy(backOff);
        template.registerListener(new RetryListener() {
            @Override
            public <T, E extends Throwable> void onError(RetryContext context,
                                                         RetryCallback<T, E> callback, Throwable throwable) {
                // 첫 실패이고 재시도 대상일 때만 — 재시도는 곧 호출 비용 2배라,
                // 빈도를 세어 두면 "재시도로 비용이 튄 날"을 뒤늦게라도 짚을 수 있다.
                if (context.getRetryCount() == 1 && isOverloaded(throwable)) {
                    log.warn("{} 503(혼잡) — {}초 뒤 1회 재시도", properties.getProvider(), RETRY_DELAY_MILLIS / 1000);
                    Metrics.counter("llm.retries", "provider", properties.getProvider()).increment();
                }
            }
        });
        return template;
    }

    private boolean isOverloaded(Throwable throwable) {
        for (Throwable cur = throwable; cur != null; cur = cur.getCause() == cur ? null : cur.getCause()) {
            if (cur instanceof ApiException api && api.code() == 503) {
                return true;
            }
        }
        return false;
    }
}
