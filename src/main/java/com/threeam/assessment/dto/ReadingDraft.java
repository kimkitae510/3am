package com.threeam.assessment.dto;

import java.util.Map;

// 정밀 판독 LLM(2호출)의 파싱 결과 — 아직 판정 id가 붙기 전의 순수 산출물.
// 표지(overall + 올린/막는 이유)와 여섯 장(상대의 지금, 결심, 남은 마음, 왜 멀어졌나,
// 막는 것, 다시 움직일 조건), 국면으로 구성된다. 요인 어휘는 여기 없다 — 채점 내부 용어라
// 유저 지면에 꺼내지 않는다.
public record ReadingDraft(
        String overall,
        String coverRaise,
        String coverBlock,
        Section now,
        Section resolve,
        Section remain,
        String drift,
        String blocking,
        Reselect reselect,
        String phase,
        Map<String, String> chapterTitles) {

    public record Section(String state, String answer, String reading) {
    }

    public record Reselect(String state, String answer, String open, String route) {
    }
}
