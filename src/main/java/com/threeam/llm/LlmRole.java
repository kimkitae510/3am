package com.threeam.llm;

// LLM 프로토콜상의 역할. JPA의 MessageRole과 분리해 LLM 계층이 도메인 엔티티에 의존하지 않게 한다.
public enum LlmRole {
    SYSTEM,
    USER,
    ASSISTANT
}
