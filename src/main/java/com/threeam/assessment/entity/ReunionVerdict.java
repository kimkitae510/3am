package com.threeam.assessment.entity;

// 재회 진단의 큰 갈래.
// POSSIBLE=확률 산출, LET_GO=졸업 판정(확률 대신 놓아주라는 판정),
// INSUFFICIENT=판단 근거 부족(확률 대신 대화를 더 요청). INSUFFICIENT는 히스토리에 저장하지 않는다.
public enum ReunionVerdict {
    POSSIBLE,
    LET_GO,
    INSUFFICIENT
}
