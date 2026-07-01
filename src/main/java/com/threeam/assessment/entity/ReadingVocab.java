package com.threeam.assessment.entity;

import java.util.List;
import java.util.Map;

// 정밀 판독의 허용 어휘 사전. 스키마(생성 강제)와 파서(저장 전 검증)가 같은 목록을 쓴다.
// state는 유저에게 노출되지 않는 내부 값 — 판독이 매번 다른 논리로 출렁이는 걸 막고,
// 골든셋 회귀(사연 X의 정답 state)를 걸 수 있게 한다. 화면 문장은 answer가 담당한다.
public final class ReadingVocab {

    private ReadingVocab() {
    }

    // 상대의 지금 = 현재 지배적인 심리 상태
    public static final List<String> NOW_STATES = List.of(
            "EMOTIONAL_OVERWHELM", "RELATIONSHIP_RECONSIDERATION", "DETACHED", "MOVING_ON", "MIXED");

    // 결심 강도 = 관계를 끝낸다는 선택의 견고함
    public static final List<String> RESOLVE_STATES = List.of(
            "IMPULSIVE", "UNSTABLE", "MODERATE", "FIRM");

    // 남은 마음 = 애정과 미련의 잔존 정도. 재선택과 분리해서 본다.
    public static final List<String> REMAIN_STATES = List.of(
            "STRONG", "PRESENT", "WEAK", "LITTLE_EVIDENCE");

    // 재선택 가능성 = 위 셋의 종합
    public static final List<String> RESELECT_STATES = List.of(
            "OPEN", "CONDITIONAL", "NARROW", "CLOSED_CURRENTLY");

    // 장의 내부 역할 태그 — 장들이 서로 다른 판단축을 다뤘는지 점검하는 용도. 유저 비노출.
    public static final List<String> CHAPTER_ROLES = List.of(
            "CORE_CONTRADICTION", "SIGNAL_CORRECTION", "HIDDEN_SIGNAL", "DISTANCE_MEANING",
            "FEELING_VS_CHOICE", "RESPONSIBILITY", "INTERACTION");

    // 진단 항목의 키와 화면 라벨. 요인 이름(대체자 등)이 그대로 겹치는 것도 있지만,
    // 이건 채점 슬롯이 아니라 "확률을 만든 진단"의 사용자 지면 어휘다 — 판독이 유저 언어로
    // 다시 쓴다. 백엔드가 순위와 방향을 확정해 내려주고 판독은 그 순서를 바꾸지 않는다.
    public static final Map<String, String> DIAGNOSIS_LABELS = Map.of(
            "partnerSignal", "상대신호",
            "breakupReason", "이별사유",
            "resolve", "이별결심",
            "replacement", "대체자",
            "relationshipAsset", "관계자산",
            "contact", "접점",
            "userResponse", "현재대응",
            "partnerPattern", "과거패턴",
            "currentBarrier", "현재장벽");

    public static final List<String> DIAGNOSIS_KEYS = List.copyOf(DIAGNOSIS_LABELS.keySet());

    // 확률에 준 영향. 매우유리/불리 같은 내부 채점어 대신 화면은 이 어휘를 쓴다
    // (크게 높임, 높임, 영향 적음, 낮춤, 크게 낮춤).
    public static final List<String> IMPACTS = List.of(
            "STRONG_UP", "UP", "NEUTRAL", "DOWN", "STRONG_DOWN");
}
