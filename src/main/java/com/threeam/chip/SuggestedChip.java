package com.threeam.chip;

// 답변 행에 저장되는 추천 한 건. id만으로는 부족하다 — 셀렉터가 유저 상황에 맞게 다시 쓴
// 문장을 같이 남겨야 새로고침 뒤에도 그 문장이 그대로 뜬다.
//
// 겉말만 개인화하고 누르면 나가는 프롬프트는 카탈로그 고정분 그대로다.
public record SuggestedChip(String id, String label) {}
