package com.threeam.llm;

import java.util.List;
import java.util.concurrent.CompletableFuture;

// LLM 호출 추상화. 구현체(Mock/Gemini 등)를 설정으로 갈아끼운다.
// 서비스 계층은 이 인터페이스에만 의존하고 특정 벤더를 알지 못한다.
public interface LlmClient {

    // messages: SYSTEM(선택) → 이전 맥락(USER/ASSISTANT 교대) → 마지막 USER 순으로 조립해 넘긴다.
    // 논블로킹: 응답을 기다리지 않고 CompletableFuture로 돌려준다(외부 호출이 느려도 스레드를 점유하지 않게).
    // 실패 시 future가 LlmException으로 예외 완료된다.
    CompletableFuture<String> generate(List<ChatMessage> messages);

    // 구조화 출력용. 응답을 JSON 문자열로 받는다(재회 진단 등 파싱이 필요한 호출).
    // 프롬프트에서 JSON 스키마를 지시하고, 구현체는 가능하면 JSON 모드를 켠다.
    CompletableFuture<String> generateJson(List<ChatMessage> messages);
}
