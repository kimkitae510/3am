package com.threeam.story.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class StoryCreateRequest {

    // 생략 가능. 없으면 서비스에서 기본 제목을 붙인다.
    @Size(max = 100, message = "제목은 100자 이하여야 합니다.")
    private String title;
}
