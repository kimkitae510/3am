package com.threeam.llm;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// 실제 LLM 연동 전까지 사용하는 스텁. 고정 응답을 즉시 완료된 future로 돌려주어 API 키·비용 없이 전체 흐름을 검증한다.
// 실 구현(Gemini)은 llm.provider=gemini 로 두고 별도 빈으로 갈아끼운다.
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "mock", matchIfMissing = true)
public class MockLlmClient implements LlmClient {

    @Override
    public CompletableFuture<String> generate(List<ChatMessage> messages) {
        return CompletableFuture.completedFuture(
                "지금은 많이 힘든 시간일 거예요. 여기서는 천천히, 하고 싶은 만큼 이야기해도 괜찮아요. "
                        + "(개발용 임시 응답 — 실제 LLM 연동 전 고정 메시지입니다.)");
    }

    // 진단 흐름 검증용 고정 JSON. 실제 판단은 Gemini가 한다.
    @Override
    public CompletableFuture<String> generateJson(List<ChatMessage> messages) {
        return CompletableFuture.completedFuture("""
                {
                  "verdict": "POSSIBLE",
                  "breakupType": "REGRETTER",
                  "partnerType": "AMBIVALENT",
                  "deductions": [
                    {"signal": "상대가 먼저 이별을 통보", "points": 15, "evidence": "(개발용 임시 근거)"},
                    {"signal": "연락이 뜸해진 상태", "points": 5, "evidence": "(개발용 임시 근거)"}
                  ],
                  "reason": "개발용 임시 진단 — 실제 LLM 연동 전 고정 응답입니다.",
                  "summary": "개발용 임시 요약."
                }
                """);
    }
}
