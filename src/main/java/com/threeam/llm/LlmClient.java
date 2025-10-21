package com.threeam.llm;

import java.util.List;

// LLM 호출 추상화. 구현체(Mock/Anthropic 등)를 설정으로 갈아끼운다.
// 서비스 계층은 이 인터페이스에만 의존하고 특정 벤더를 알지 못한다.
public interface LlmClient {

    // messages: SYSTEM(선택) → 이전 맥락(USER/ASSISTANT 교대) → 마지막 USER 순으로 조립해 넘긴다.
    // 반환값은 어시스턴트 응답 본문. 호출 실패 시 LlmException을 던진다.
    String generate(List<ChatMessage> messages);
}
