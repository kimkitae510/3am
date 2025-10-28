package com.threeam.assessment.dto;

import com.threeam.llm.ChatMessage;
import java.util.List;

// 진단에 필요한 재료를 짧은 트랜잭션에서 모아 담는 홀더.
// LLM 호출은 이 재료를 들고 트랜잭션 밖에서 일어난다.
public record AssessmentContext(String memorySummary, List<ChatMessage> conversation) {

    public List<String> rawTexts() {
        return conversation.stream().map(ChatMessage::content).toList();
    }
}
