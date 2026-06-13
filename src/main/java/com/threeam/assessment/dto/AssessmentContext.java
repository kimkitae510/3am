package com.threeam.assessment.dto;

import com.threeam.llm.ChatMessage;
import java.util.List;

// 분석에 필요한 재료를 짧은 트랜잭션에서 모아 담는 홀더.
// LLM 호출은 이 재료를 들고 트랜잭션 밖에서 일어난다.
// knownFactLines: 기록일이 붙은 사실 원장 줄들(예: "(11/10) 상대가 먼저 이별 통보").
// todayLine: 오늘 날짜 안내 — 루브릭 시간 규칙의 기준점.
// previousDigest: 직전 분석 요지(유형, 확률, 요인 판정) — 없으면 null.
// 기억 요약은 싣지 않는다 — 같은 대화의 파생 정보라 새 정보가 없고,
// 직전 분석이 쓴 요약이 다음 분석에 들어가는 반향실이 된다(v2에서 제거).
public record AssessmentContext(List<String> knownFactLines, List<ChatMessage> conversation,
                                String todayLine, String previousDigest) {
}
