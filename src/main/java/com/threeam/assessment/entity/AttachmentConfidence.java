package com.threeam.assessment.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 애착유형 판정의 확신도. 행동 관찰만으로 유형을 확정하는 건 한계가 있어(자기보고 없이 서사 기반),
// 근거가 여러 상황에 걸쳐 충분할 때만 CONFIRMED, 근거가 얇거나 이별 국면에 몰려 있으면 TENTATIVE.
@Getter
@RequiredArgsConstructor
public enum AttachmentConfidence {
    CONFIRMED("확정"),
    TENTATIVE("추정");

    private final String label;
}
