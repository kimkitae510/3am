package com.threeam.story.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class MessageSendRequest {

    @NotBlank(message = "메시지 내용은 필수입니다.")
    // LLM 입력 비용 방어. 4000자는 사실상 무제한이라 1000자로 조인다(프론트 카운터와 동일 값).
    @Size(max = 1000, message = "메시지는 1000자까지 보낼 수 있어요.")
    private String content;
}
