package com.threeam.assessment.dto;

import java.util.List;

// 정밀 판독(2호출)의 산출물 — 스토리북 구조(지시 전문은 로컬 reading.yml).
// 표지(숫자 + 한 줄 판정 + 가장 큰 이유 하나) → 사연별 미스터리 3~5장 → 장애물 1~2개 →
// 다시 움직일 조건 → 국면 + 칩 씨앗.
// 유저 질문은 별도 섹션이 아니라 중요하면 미스터리 장으로 승격된다(고정 QA 페이지는 인공적이다).
// 관계 복구 원리도 독립 장이 아니라 상호작용을 다룬 장의 principle로 붙는다.
// 이 객체가 그대로 JSON 직렬화되어 assessment_reading.body에 저장되고 화면으로 내려간다.
public record ReadingDraft(
        String coverVerdict,
        String coverReason,
        List<Mystery> mysteries,
        List<Blocker> blockers,
        Reselect reselect,
        Phase phase,
        FollowUp followUp,           // 다음 재진단을 바꿀 질문이 없으면 null
        Internal internal) {

    // principle: 이 장이 상호작용 충돌을 다뤘을 때만 "이런 충돌을 줄이려면"의 복구 원리. 그 외 null.
    public record Mystery(String title, String answer, String reading, String principle,
                          List<String> evidenceIds, List<String> covers) {
    }

    public record Blocker(int rank, String title, String answer, String reading,
                          List<String> evidenceIds) {
    }

    public record Reselect(String title, String answer, List<String> open,
                           List<String> conditions, List<String> watchFor) {
    }

    public record Phase(String label, String reading, List<String> chipSeeds) {
    }

    public record FollowUp(String question, String whyItMatters) {
    }

    public record Internal(String nowState, String resolveState, String remainState,
                           String reselectState) {
    }
}
