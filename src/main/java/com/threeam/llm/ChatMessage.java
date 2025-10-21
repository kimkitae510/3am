package com.threeam.llm;

public record ChatMessage(LlmRole role, String content) {

    public static ChatMessage system(String content) {
        return new ChatMessage(LlmRole.SYSTEM, content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(LlmRole.USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(LlmRole.ASSISTANT, content);
    }
}
