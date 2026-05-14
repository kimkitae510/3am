package com.threeam.story.dto;

import com.threeam.story.entity.StoryFact;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 진단 화면의 "사실 직접 알려주기" 입력. 한 번에 한 줄 — 원장이 사실을 한 줄씩 쌓는 구조라
// 길이 제한도 원장 한 줄(200자)과 같다. 여러 사실은 여러 번 제출한다.
public record StoryFactRequest(
        @NotBlank(message = "내용을 입력해 주세요.")
        @Size(max = StoryFact.MAX_LENGTH, message = "사실은 200자 이내로 적어 주세요.")
        String content) {
}
