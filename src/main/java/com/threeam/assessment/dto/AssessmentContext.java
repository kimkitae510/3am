package com.threeam.assessment.dto;

import com.threeam.llm.ChatMessage;
import java.util.List;

// 진단에 필요한 재료를 짧은 트랜잭션에서 모아 담는 홀더.
// LLM 호출은 이 재료를 들고 트랜잭션 밖에서 일어난다.
// knownFactLines: 기록일이 붙은 사실 원장 줄들(예: "(11/10) 상대가 먼저 이별 통보").
// previousAttachment: 직전 진단의 상대 유형(라벨+확신도, 없으면 null) — 반증 없는 판정 비움 방지용.
public record AssessmentContext(String memorySummary, List<String> knownFactLines,
                                List<ChatMessage> conversation, String previousAttachment) {

    public AssessmentContext(String memorySummary, List<String> knownFactLines,
                             List<ChatMessage> conversation) {
        this(memorySummary, knownFactLines, conversation, null);
    }
}
