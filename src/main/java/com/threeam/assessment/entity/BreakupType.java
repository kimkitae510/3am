package com.threeam.assessment.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 내 이별 유형.
@Getter
@RequiredArgsConstructor
public enum BreakupType {
    CLINGER("매달림형"),
    REGRETTER("후회형"),
    SELF_BLAMER("자책형");

    private final String label;
}
