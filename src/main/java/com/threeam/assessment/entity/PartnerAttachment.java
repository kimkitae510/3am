package com.threeam.assessment.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 상대 애착유형. 원장과 대화에 쌓인 상대의 '행동 패턴'으로 LLM이 분류한다.
// 도덕 평가가 아니라 패턴 분류 — 근거가 부족하면 null로 남긴다.
// 라벨은 이별 커뮤니티에서 통용되는 용어를 따른다(거부회피형=거회, 공포회피형=공회).
@Getter
@RequiredArgsConstructor
public enum PartnerAttachment {
    SECURE("안정형"),
    ANXIOUS("불안형"),
    AVOIDANT("거부회피형"),
    FEARFUL("공포회피형");

    private final String label;
}
