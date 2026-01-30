package com.threeam.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GeminiLlmClientTest {

    private GeminiLlmClient client(String assessmentModel) {
        GeminiProperties props = new GeminiProperties();
        props.setApiKey("test-key");
        props.setModel("gemini-2.5-flash");
        props.setAssessmentModel(assessmentModel);
        return new GeminiLlmClient(props, new ObjectMapper());
    }

    @Test
    @DisplayName("진단 전용 모델이 비어 있으면 deep도 기본 모델 엔드포인트로 떨어진다")
    void deepEndpointFallsBack() {
        GeminiLlmClient client = client(null);

        assertThat(client.deepEndpoint()).isEqualTo(client.endpoint());
        assertThat(client.endpoint()).contains("/models/gemini-2.5-flash:generateContent");
    }

    @Test
    @DisplayName("진단 전용 모델을 지정하면 deep 엔드포인트만 그 모델을 쓴다 — vertex와 동일한 분리")
    void deepEndpointUsesOverride() {
        GeminiLlmClient client = client("gemini-2.5-pro");

        assertThat(client.deepEndpoint()).contains("/models/gemini-2.5-pro:generateContent");
        assertThat(client.endpoint()).contains("/models/gemini-2.5-flash:generateContent");
    }
}
