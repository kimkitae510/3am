package com.threeam.assessment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

// 정밀 판독(2호출)의 산출물 — 스토리북 v4 계약(지시 전문은 로컬 reading.yml, 출력 형식과 1:1).
// 표지 총평은 없다: 화면이 확률과 등급을 먼저 보여주고, probabilityReading이 "왜 이 숫자인지"의
// 미니 판독을 단다. 본문은 사연별 chapters(모순, 신호 교정, 숨은 신호...)이고, 관계심리와
// 복구 원리는 독립 장이 아니라 chapter 안에 붙는다. followUp은 없다 — 결제한 리포트 끝에
// "분석이 덜 끝났다"는 느낌을 만들지 않는다.
// 이 객체가 그대로 JSON 직렬화되어 assessment_reading.body에 저장되고 화면으로 내려간다.
public record ReadingDraft(
        ProbabilityReading probabilityReading,
        List<Chapter> chapters,
        Barrier currentBarrier,          // 지금 재선택을 막는 직접 장애물. 없으면 null
        Barrier secondaryBarrier,        // 정말 독립적인 두 번째 현실 장벽일 때만
        Maintenance maintenanceInsight,  // 재회 후 반복 위험 — 물었거나 반복이 명확할 때만
        Reselect reselect,
        // 마지막 화면(stateLabel + 칩). 자바에서 final이 예약어라 필드명은 fin,
        // 저장/응답 JSON에서는 계약대로 "final"로 나간다.
        @JsonProperty("final") Fin fin,
        Internal internal) {

    public record ProbabilityReading(String reading, List<String> evidenceIds) {
    }

    // eyebrow: 왜 이 장을 읽는지 먼저 알려주는 짧은 문구. chapterRole은 내부 값.
    // psychology와 repairPrinciple은 그 장이 관계심리/반복 충돌을 다뤘을 때만 붙는다.
    public record Chapter(String eyebrow, String title, String chapterRole, String answer,
                          String reading, Psychology psychology, String repairPrinciple,
                          List<String> evidenceIds) {
    }

    public record Psychology(String concept, String reading) {
    }

    public record Barrier(String answer, String reading, List<String> evidenceIds) {
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
