package com.threeam.assessment.entity;

import java.util.List;

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
}
