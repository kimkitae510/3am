package com.threeam.assessment.dto;

import java.util.List;

// 정밀 판독(2호출)의 산출물 — 스토리북 v2 구조(지시 전문은 로컬 reading.yml).
// 표지(한 문장 판정 + 이유 하나) → 사연별 미스터리 → 유저 질문 → 막는 것 순위 →
// 반복 위험과 복구 원리 → 다시 움직일 조건 → 국면 + 칩 씨앗.
// 이 객체가 그대로 JSON 직렬화되어 assessment_reading.body에 저장되고 화면으로 내려간다 —
// 구조가 자리 잡는 중이라 컬럼으로 파지 않는다(바뀔 때마다 DDL이 따라붙는 걸 피함).
// internal의 state 4개만 검색 가능한 컬럼으로 따로 뽑아 저장한다(골든셋 회귀용).
public record ReadingDraft(
        String coverVerdict,
        String coverReason,
        List<Mystery> mysteries,
        List<Question> questions,
        List<Blocker> blockers,
        Repair relationshipRepair,   // 읽을 재료가 없으면 null
        Reselect reselect,
        Phase phase,
        FollowUp followUp,           // 다음 재진단을 바꿀 질문이 없으면 null
        Internal internal) {

    public record Mystery(String title, String answer, String reading,
                          List<String> evidenceIds, List<String> covers) {
    }

    public record Question(String source, String question, String answer, String reading,
                           List<String> evidenceIds) {
    }

    public record Blocker(int rank, String title, String answer, String reading,
                          List<String> evidenceIds) {
    }

    public record Repair(String title, String answer, String concept, String reading,
                         String repairPrinciple) {
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
