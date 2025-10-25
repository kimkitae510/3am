package com.threeam.assessment.dto;

// 이별 사유. CHEATING(신뢰 붕괴)은 졸업 판정 트리거.
public enum BreakupReason {
    EXTERNAL,      // 장거리·타이밍 등 외부 요인
    BOREDOM,       // 권태
    PERSONALITY,   // 성격 차이·반복 갈등
    CHEATING       // 바람·신뢰 붕괴
}
