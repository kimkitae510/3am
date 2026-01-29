package com.threeam.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;

class SpringAiLlmClientTest {

    private final ChatModel chatModel = mock(ChatModel.class);
    private final LlmProperties properties = new LlmProperties();
    private SpringAiLlmClient client;

    @BeforeEach
    void setUp() {
        properties.setProvider("gemini");
        properties.setModel("test-flash");
        // 테스트는 호출 스레드에서 즉시 실행 — 풀 스케줄링이 아니라 옵션/변환 로직을 검증한다.
        client = new SpringAiLlmClient(chatModel, properties, Runnable::run);
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private Prompt captured() {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("여러 SYSTEM 조각은 하나의 SystemMessage로 순서대로 병합된다 — GenAI 모듈이 첫 개만 싣기 때문")
    void mergesSystemMessages() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("답변"));

        String result = client.generate(List.of(
                ChatMessage.system("페르소나"),
                new ChatMessage(LlmRole.USER, "안녕"),
                ChatMessage.system("스타일 리마인더"))).join();

        assertThat(result).isEqualTo("답변");
        Prompt prompt = captured();
        List<org.springframework.ai.chat.messages.Message> systems = prompt.getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.SYSTEM).toList();
        assertThat(systems).hasSize(1);
        assertThat(systems.get(0).getText()).isEqualTo("페르소나\n스타일 리마인더");
        assertThat(prompt.getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.USER).toList()).hasSize(1);
    }

    @Test
    @DisplayName("generate는 기본 모델 + thinking 최소화(비용 — thinking이 출력 과금의 90%)")
    void generateUsesMinimalThinking() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("답변"));

        client.generate(List.of(new ChatMessage(LlmRole.USER, "안녕"))).join();

        GoogleGenAiChatOptions options = (GoogleGenAiChatOptions) captured().getOptions();
        assertThat(options.getModel()).isEqualTo("test-flash");
        assertThat(options.getThinkingLevel()).isEqualTo(GoogleGenAiThinkingLevel.MINIMAL);
        assertThat(options.getResponseMimeType()).isNull();
    }

    @Test
    @DisplayName("generateJson은 JSON 모드를 켠다 — 코드펜스, 잡설 없는 순수 JSON 강제")
    void generateJsonForcesJsonMode() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("{}"));

        client.generateJson(List.of(new ChatMessage(LlmRole.USER, "추출"))).join();

        GoogleGenAiChatOptions options = (GoogleGenAiChatOptions) captured().getOptions();
        assertThat(options.getResponseMimeType()).isEqualTo("application/json");
        assertThat(options.getThinkingLevel()).isEqualTo(GoogleGenAiThinkingLevel.MINIMAL);
    }

    @Test
    @DisplayName("generateJsonDeep은 진단 전용 모델 + temperature 0, thinking은 기본 유지")
    void deepUsesAssessmentModel() {
        properties.setAssessmentModel("test-pro");
        when(chatModel.call(any(Prompt.class))).thenReturn(response("{}"));

        client.generateJsonDeep(List.of(new ChatMessage(LlmRole.USER, "진단"))).join();

        GoogleGenAiChatOptions options = (GoogleGenAiChatOptions) captured().getOptions();
        assertThat(options.getModel()).isEqualTo("test-pro");
        assertThat(options.getTemperature()).isEqualTo(0.0);
        assertThat(options.getResponseMimeType()).isEqualTo("application/json");
        assertThat(options.getThinkingLevel()).isNull();
    }

    @Test
    @DisplayName("진단 전용 모델이 비어 있으면 deep도 기본 모델로 떨어진다")
    void deepFallsBackToDefaultModel() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("{}"));

        client.generateJsonDeep(List.of(new ChatMessage(LlmRole.USER, "진단"))).join();

        assertThat(((GoogleGenAiChatOptions) captured().getOptions()).getModel()).isEqualTo("test-flash");
    }

    @Test
    @DisplayName("호출 실패는 LlmException으로 완료된다 — 전역 핸들러가 502로 응답하게")
    void wrapsFailureAsLlmException() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("Failed to generate content"));

        assertThatThrownBy(() -> client.generate(List.of(new ChatMessage(LlmRole.USER, "안녕"))).join())
                .hasCauseInstanceOf(LlmException.class);
    }

    @Test
    @DisplayName("빈 응답 텍스트도 LlmException — 빈 답이 정상 저장되는 것을 막는다")
    void emptyResponseIsFailure() {
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of()));

        assertThatThrownBy(() -> client.generate(List.of(new ChatMessage(LlmRole.USER, "안녕"))).join())
                .hasCauseInstanceOf(LlmException.class);
    }
}
