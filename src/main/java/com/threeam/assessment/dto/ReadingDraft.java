package com.threeam.assessment.dto;

import java.util.List;
import java.util.Map;

// 정밀 판독 LLM(2호출)의 파싱 결과 — 아직 판정 id가 붙기 전의 순수 산출물.
// state는 ReadingVocab에서 검증된 값, 라벨류(question/source/direction)도 검증 후 담긴다.
public record ReadingDraft(
        String overall,
        String narrative,
        Section now,
        Section resolve,
        Section remain,
        Reselect reselect,
        List<Evidence> evidence,
        String phase,
        Map<String, String> chapterTitles) {

    public record Section(String state, String answer, String reading) {
    }

    public record Reselect(String state, String answer, String closed, String open, String route) {
    }

    public record Evidence(String question, String source, String name, String direction,
                           String fact, String interpretation) {
    }
}
