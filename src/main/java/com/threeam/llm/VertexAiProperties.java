package com.threeam.llm;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

// Vertex AI 연동 설정. 인증 키 파일 경로는 여기 두지 않는다 —
// google-auth가 표준 환경변수(GOOGLE_APPLICATION_CREDENTIALS)에서 직접 읽는다.
@Getter
@Setter
@ConfigurationProperties(prefix = "llm.vertex")
public class VertexAiProperties {

    private String projectId;
    private String location = "global";
    private String model = "gemini-2.5-flash";

    // 응답 전체 대기 상한. 초과 시 호출이 실패로 완료되고 폴백 메시지가 저장된다.
    private long timeoutSeconds = 30;

    // 리전 엔드포인트는 호스트에 리전 접두사가 붙고, global 엔드포인트는 접두사가 없다.
    public String endpoint() {
        String host = "global".equals(location)
                ? "aiplatform.googleapis.com"
                : location + "-aiplatform.googleapis.com";
        return "https://" + host + "/v1/projects/" + projectId
                + "/locations/" + location
                + "/publishers/google/models/" + model + ":generateContent";
    }
}
