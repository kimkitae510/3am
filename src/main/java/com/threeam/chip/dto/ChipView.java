package com.threeam.chip.dto;

import com.threeam.chip.ChipDefinition;
import com.threeam.chip.ChipInputPreset;
import com.threeam.chip.ChipInteraction;

// 화면에 나가는 칩. 모듈과 마이크로 프롬프트는 서비스 자산이라 절대 싣지 않는다 —
// 화면이 알아야 하는 건 뭐라고 그릴지(label)와 누르면 뭘 할지(interaction, preset)뿐이다.
public record ChipView(
        String id,
        String label,
        String module,
        ChipInteraction interaction,
        ChipInputPreset inputPreset
) {

    public static ChipView of(ChipDefinition chip, ChipInputPreset preset) {
        return of(chip, preset, null);
    }

    // label을 따로 받는 것은 추천 3개뿐이다 — 셀렉터가 유저 상황에 맞게 다시 쓴 문장이 온다.
    // 비면 카탈로그 원문으로 돌아간다. 전체 목록("다른 질문 보기")은 언제나 원문이다.
    public static ChipView of(ChipDefinition chip, ChipInputPreset preset, String label) {
        String shown = (label == null || label.isBlank()) ? chip.label() : label;
        return new ChipView(chip.id(), shown, chip.module(), chip.interaction(), preset);
    }
}
