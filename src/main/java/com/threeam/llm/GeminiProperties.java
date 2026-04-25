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

    // 정밀 판단(재회 진단) 전용 모델. 비우면 기본 model을 그대로 쓴다.
    // 원래 vertex 경로에만 있던 분리인데, 크레딧 소진 후 gemini 경로로 복귀해도
    // 진단만 강한 모델을 유지할 수 있게 동일하게 지원한다.
    private String assessmentModel;

    private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";

    // 응답 전체 대기 상한. 초과 시 호출이 실패로 완료되고 폴백 메시지가 저장된다.
    private long timeoutSeconds = 50;

    // 진단(deep) 전용 응답 상한. 채팅과 분리해 둔다 — 전에는 채팅 값의 3배로 계산했는데,
    // 채팅 타임아웃을 만질 때마다 진단이 조용히 따라 움직여 usage.in-flight-ttl-seconds와
    // spring request-timeout을 넘길 뻔했다(실측). 두 값은 각자의 이유로 정해져야 한다.
    private long assessmentTimeoutSeconds = 90;

    // thinking 세기. 제어 필드가 세대마다 달라 둘을 따로 둔다 — 2.5 계열은 토큰 예산(thinkingBudget),
    // 3.x는 단계(thinkingLevel). 모델을 바꿀 때 코드를 안 고쳐도 되게 설정으로 뺐다:
    // 전에는 2.5가 아니면 무조건 low로 떨어져서, 더 강한 모델로 갈아타는 순간 추론만 낮아졌다.
    private int thinkingBudget = 2048;
    private String thinkingLevel = "low";
}
