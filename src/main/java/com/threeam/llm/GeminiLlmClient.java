package com.threeam.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Gemini API(AI Studio) 실연동. API 키 하나로 인증하는 개인 개발자용 경로.
// llm.provider=gemini 일 때만 빈으로 등록된다(기본은 Mock).
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "gemini")
public class GeminiLlmClient extends GoogleGenerateContentClient {

    private final GeminiProperties properties;

    public GeminiLlmClient(GeminiProperties properties, ObjectMapper objectMapper) {
        super(objectMapper);
        this.properties = properties;
    }

    @Override
    String endpoint() {
        return endpointFor(properties.getModel());
    }

    @Override
    String deepEndpoint() {
        String assessmentModel = properties.getAssessmentModel();
        return endpointFor(assessmentModel == null || assessmentModel.isBlank()
                ? properties.getModel() : assessmentModel);
    }


    // 판별 전용 모델. 비우면 채팅 모델을 그대로 쓴다 — 설정을 안 넣어도 돌아야 한다.
    @Override
    String quickEndpoint() {
        String matchModel = properties.getMatchModel();
        return endpointFor(matchModel == null || matchModel.isBlank()
                ? properties.getModel() : matchModel);
    }

    @Override
    String quickThinkingLevel() {
        return properties.getMatchThinkingLevel();
    }

    @Override
    int quickThinkingBudget() {
        return properties.getMatchThinkingBudget();
    }

    private String endpointFor(String model) {
        return properties.getBaseUrl() + "/models/" + model + ":generateContent";
    }

    @Override
    void authorize(HttpRequest.Builder builder) {
        // 키를 쿼리스트링에 두면 접근 로그, 프록시에 남을 수 있어 헤더로 보낸다.
        builder.header("x-goog-api-key", properties.getApiKey());
    }

    @Override
    long timeoutSeconds() {
        return properties.getTimeoutSeconds();
    }

    @Override
    long assessmentTimeoutSeconds() {
        return properties.getAssessmentTimeoutSeconds();
    }

    @Override
    int thinkingBudget() {
        return properties.getThinkingBudget();
    }

    @Override
    String thinkingLevel() {
        return properties.getThinkingLevel();
    }

    @Override
    double temperature() {
        return properties.getTemperature();
    }

    @Override
    int assessmentThinkingBudget() {
        return properties.getAssessmentThinkingBudget();
    }

    @Override
    String assessmentThinkingLevel() {
        return properties.getAssessmentThinkingLevel();
    }

    @Override
    double[] pricesPerMillion() {
        return new double[] {properties.getInputPricePerMillion(),
                properties.getCachedInputPricePerMillion(),
                properties.getOutputPricePerMillion()};
    }

    @Override
    String providerName() {
        return "Gemini";
    }
}
