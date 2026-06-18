package com.threeam.story.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class MessageSendRequest {

    @NotBlank(message = "메시지 내용은 필수입니다.")
    // LLM 입력 비용 방어 — 대화 창(최근 20개)에 실려 매 호출 반복 과금되는 게 진짜 비용.
    // 500자를 넘는 메시지는 500자마다 대화 1회로 환산 차감된다(StoryService.CHAT_UNIT_CHARS).
    // 상한 2000자: 긴 사연을 600자에서 끊어 흐름이 깨지던 실측 대응(그 이상은 히스토리 누적 방어).
    // 프론트 카운터(MAX_LENGTH)와 동일 값 유지.
    @Size(max = 3000, message = "메시지는 3000자까지 보낼 수 있습니다.")
    private String content;

    // 추천 질문 칩에서 온 말이면 그 칩의 id. 자유입력이면 null이다 —
    // 칩은 질문 범위를 제한하는 장치가 아니라 전문 프롬프트로 가는 지름길이라, 없어도 상담은 돈다.
    // 카탈로그에 없는 값이면 서버가 무시하고 자유입력으로 다룬다(칩을 지운 뒤 열려 있던 화면).
    @Size(max = 40, message = "칩 식별자가 너무 깁니다.")
    private String chipId;
}
