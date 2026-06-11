package com.threeam.story.dto;

// 유저가 직접 적은 사실의 생성 응답. id는 화면이 취소/수정을 걸 때 쓴다.
public record StoryFactCreateResponse(Long id) {
}
