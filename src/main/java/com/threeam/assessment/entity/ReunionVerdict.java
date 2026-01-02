package com.threeam.assessment.entity;

// 재회 진단의 큰 갈래.
// POSSIBLE=확률 산출, INSUFFICIENT=판단 근거 부족(확률 대신 대화를 더 요청, 히스토리 미저장).
// DATING=아직 사귀는 중 — 재회 확률은 이별 전제라 산출하지 않되(구조적 잠금),
//   애착유형은 관계 상태와 무관한 행동 패턴이므로 판정해서 저장한다. 프롬프트 방어만으로는
//   커플 고백 뒤에도 이전 확률이 화면에 남았다 — 저장되는 정식 판정으로 만들어 최신 결과를 교체한다.
// LET_GO(놓아주기)는 폐기 — "못 놓아서 온 사람"에게 놓아주라는 판정은 하지 않는다.
//   가망 낮은 케이스도 낮은 확률(POSSIBLE)로 표현한다. 과거 데이터 호환 위해 상수만 남겨둔다.
public enum ReunionVerdict {
    POSSIBLE,
    INSUFFICIENT,
    DATING,
    @Deprecated
    LET_GO
}
