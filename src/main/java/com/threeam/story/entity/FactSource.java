package com.threeam.story.entity;

// 원장 한 줄의 출처. 추출(EXTRACTED)은 LLM이 대화에서 뽑은 것, USER는 유저가 분석 화면에서
// 직접 적어준 것 — 재분석 가드가 "새 재료"로 인정하는 근거이자, 프롬프트에서 유저 주장임을
// 표시하는 라벨(루브릭의 "유저 말보다 상대 행동 우선" 원칙이 걸리게).
public enum FactSource {
    EXTRACTED,
    USER
}
