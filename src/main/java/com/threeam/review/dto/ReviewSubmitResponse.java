package com.threeam.review.dto;

// 점수 제출 즉시 지급된 보상 안내. 화면 문구가 서버 설정과 어긋나지 않게 값으로 내린다.
public record ReviewSubmitResponse(int chatBonus) {
}
