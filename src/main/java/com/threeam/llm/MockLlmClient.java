package com.threeam.llm;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// 실제 LLM 연동 전까지 사용하는 스텁. 고정 응답을 돌려주어 API 키·비용 없이 전체 흐름을 검증한다.
// 실 구현(Anthropic 등)은 llm.provider=anthropic 으로 두고 별도 빈으로 갈아끼운다.
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "mock", matchIfMissing = true)
public class MockLlmClient implements LlmClient {

    @Override
    public String generate(List<ChatMessage> messages) {
        return "지금은 많이 힘든 시간일 거예요. 여기서는 천천히, 하고 싶은 만큼 이야기해도 괜찮아요. "
                + "(개발용 임시 응답 — 실제 LLM 연동 전 고정 메시지입니다.)";
    }
}
