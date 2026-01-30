package com.threeam.llm;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

// Gemini 연동 설정. 실제 키는 환경변수(LLM_API_KEY)로 주입한다.
@Getter
@Setter
@ConfigurationProperties(prefix = "llm.gemini")
public class GeminiProperties {

    private String apiKey;
    private String model = "gemini-2.5-flash-lite";
    private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";

    // 응답 전체 대기 상한. 초과 시 호출이 실패로 완료되고 폴백 메시지가 저장된다.
    private long timeoutSeconds = 30;
}
