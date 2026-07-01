package com.threeam.assessment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

// 정밀 판독(2호출)의 산출물 — v7 계약(지시 전문은 로컬 reading.yml, 출력 형식과 1:1).
// 진단이 먼저다: 확률 게이지 아래 "재회 가능성을 만든 진단"이 오고, 심층 장면(analysisChapters)이
// 그 뒤를 받친다. 방향과 순위는 백엔드가 확정해 내려준 것을 판독이 유저 언어로 다시 쓸 뿐,
// 판독이 순위를 바꾸지 않는다.
// 이 객체가 그대로 JSON 직렬화되어 assessment_reading.body에 저장되고 화면으로 내려간다.
public record ReadingDraft(
        String diagnosisSummary,
        List<Diagnosis> diagnosis,
        List<Chapter> analysisChapters,
        Maintenance maintenanceInsight,  // 재회 후 반복 위험 — 물었거나 반복이 명확할 때만
        Reselect reselect,
        // 마지막 화면(stateLabel + 칩). 자바에서 final이 예약어라 필드명은 fin,
        // 저장/응답 JSON에서는 계약대로 "final"로 나간다.
        @JsonProperty("final") Fin fin,
        Internal internal) {

    // impact는 확률에 준 영향(STRONG_UP..STRONG_DOWN) — 화면이 "크게 높임" 같은 말로 번역한다.
    public record Diagnosis(String key, String label, int rank, String impact, String verdict,
                            String reading, List<String> evidenceIds) {
    }

    // eyebrow: 왜 이 장을 읽는지 먼저 알려주는 짧은 문구. chapterRole과 interpretationId는 내부 값.
    public record Chapter(String eyebrow, String title, String chapterRole, String interpretationId,
                          String answer, String reading, Psychology psychology,
                          String repairPrinciple, List<String> evidenceIds) {
    }

    public record Psychology(String concept, String reading) {
    }

    public record Maintenance(String title, String answer, Psychology psychology,
                              String reading, String repairPrinciple) {
    }

    public record Reselect(String title, String answer, String reading,
                           List<String> turningPoints) {
    }

    public record Fin(String stateLabel, List<String> chipSeeds) {
    }

    public record Internal(String nowState, String resolveState, String remainState,
                           String reselectState) {
    }
}
