package com.threeam.assessment.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 상대 성향.
@Getter
@RequiredArgsConstructor
public enum PartnerType {
    DECISIVE("단호 손절형"),
    AMBIVALENT("미련 남은 밀당형"),
    COLD("냉담형");

    private final String label;
}
