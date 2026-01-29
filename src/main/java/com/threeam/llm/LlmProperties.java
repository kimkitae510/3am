package com.threeam.llm;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

// LLM 연동 설정. 인증 경로(gemini=AI Studio API 키 / vertex=GCP ADC)와 무관하게 한 벌로 통합 —
// Spring AI GenAI 모듈이 두 경로를 같은 클라이언트로 다루므로 설정도 나눌 이유가 없어졌다.
@Getter
@Setter
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    private String provider = "mock";

    // gemini(AI Studio) 경로 인증. 실제 키는 환경변수(LLM_API_KEY)로 주입한다.
    private String apiKey;

    // vertex 경로. 자격증명 파일 경로는 여기 두지 않는다 —
    // SDK가 표준 환경변수(GOOGLE_APPLICATION_CREDENTIALS)에서 직접 읽는다(ADC).
    private String projectId;
    private String location = "global";

    private String model = "gemini-2.5-flash";

    // 정밀 판단(재회 진단) 전용 모델. 비우면 기본 model을 그대로 쓴다.
    // 진단은 긴 루브릭 일관 적용이 필요해 채팅보다 강한 모델을 배정할 수 있게 분리.
    private String assessmentModel;

    // 응답 전체 대기 상한. 초과 시 호출이 실패로 완료되고 폴백 메시지가 저장된다.
    private long timeoutSeconds = 30;

    public String assessmentModelOrDefault() {
        return assessmentModel == null || assessmentModel.isBlank() ? model : assessmentModel;
    }
}
