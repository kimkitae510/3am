package com.threeam.assessment.entity;

// 재회 진단의 큰 갈래.
// POSSIBLE=확률 산출, INSUFFICIENT=판단 근거 부족(확률 대신 대화를 더 요청, 히스토리 미저장).
// LET_GO(놓아주기)는 폐기 — "못 놓아서 온 사람"에게 놓아주라는 판정은 하지 않는다.
//   가망 낮은 케이스도 낮은 확률(POSSIBLE)로 표현한다. 과거 데이터 호환 위해 상수만 남겨둔다.
public enum ReunionVerdict {
    POSSIBLE,
    INSUFFICIENT,
    @Deprecated
    LET_GO
}
