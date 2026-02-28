package com.threeam.story.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class MessageSendRequest {

    @NotBlank(message = "메시지 내용은 필수입니다.")
    // LLM 입력 비용 방어 — 대화 창(최근 20개)에 실려 매 호출 반복 과금되는 게 진짜 비용.
    // 150자를 넘는 메시지는 150자마다 대화 1회로 환산 차감된다(StoryService.CHAT_UNIT_CHARS).
    // 프론트 카운터(MAX_LENGTH)와 동일 값 유지.
    @Size(max = 600, message = "메시지는 600자까지 보낼 수 있어요.")
    private String content;
}
