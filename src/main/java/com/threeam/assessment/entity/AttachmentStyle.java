package com.threeam.assessment.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 애착유형. 유저와 상대 모두 이 하나의 분류로 판정한다(기존 매달림형/단호손절형 등 커스텀 유형 폐기).
// 원장과 대화에 쌓인 '행동 패턴'으로 LLM이 분류하고, 근거가 부족하면 null로 남긴다.
// 라벨은 이별 커뮤니티에서 통용되는 용어를 따른다(거부회피형=거회, 공포회피형=공회).
@Getter
@RequiredArgsConstructor
public enum AttachmentStyle {
    SECURE("안정형"),
    ANXIOUS("불안형"),
    AVOIDANT("거부회피형"),
    FEARFUL("공포회피형");

    private final String label;
}
