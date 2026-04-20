package com.threeam.llm;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

// LLM 호출 추상화. 구현체(Mock/Gemini 등)를 설정으로 갈아끼운다.
// 서비스 계층은 이 인터페이스에만 의존하고 특정 벤더를 알지 못한다.
public interface LlmClient {

    // messages: SYSTEM(선택) → 이전 맥락(USER/ASSISTANT 교대) → 마지막 USER 순으로 조립해 넘긴다.
    // 논블로킹: 응답을 기다리지 않고 CompletableFuture로 돌려준다(외부 호출이 느려도 스레드를 점유하지 않게).
    // 실패 시 future가 LlmException으로 예외 완료된다.
    CompletableFuture<String> generate(List<ChatMessage> messages);

    // 구조화 출력용. 응답을 JSON 문자열로 받는다(사실 추출 등 파싱이 필요한 호출).
    // 프롬프트에서 JSON 스키마를 지시하고, 구현체는 가능하면 JSON 모드를 켠다.
    CompletableFuture<String> generateJson(List<ChatMessage> messages);

    // 정밀 판단용 JSON 호출(재회 진단). 긴 루브릭을 일관 적용해야 해서 구현체가
    // 더 강한 모델을 배정할 수 있다. 기본은 generateJson과 동일(분리 설정이 없을 때).
    default CompletableFuture<String> generateJsonDeep(List<ChatMessage> messages) {
        return generateJsonDeep(messages, null);
    }

    // responseSchema를 함께 넘기는 정밀 판단 호출. 프롬프트로 JSON을 "부탁"하는 것과 달리,
    // 스키마는 생성 단계에서 문법을 강제해 잘린 JSON, 오타난 필드, 범위 밖 enum이 아예 나올 수 없게 한다
    // (실측: 프롬프트 지시만으로는 finishReason=STOP인데 본문이 중간에 끊기는 실패가 반복).
    // 스키마 표현은 벤더 형식(Google의 OpenAPI 서브셋)이라 구현체가 해석한다. null이면 지시 없이 호출.
    default CompletableFuture<String> generateJsonDeep(List<ChatMessage> messages,
                                                       Map<String, Object> responseSchema) {
        return generateJson(messages);
    }
}
