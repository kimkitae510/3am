package com.threeam.assessment.entity;

// 재회 진단의 큰 갈래.
// POSSIBLE=확률 산출, LET_GO=졸업 판정(놓아주라는 판정), DANGER=위기(폭력·자해) → 확률 대신 안전 안내.
public enum ReunionVerdict {
    POSSIBLE,
    LET_GO,
    DANGER
}
