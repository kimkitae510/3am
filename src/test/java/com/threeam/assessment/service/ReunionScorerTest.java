package com.threeam.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.threeam.assessment.entity.Deduction;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReunionScorerTest {

    private final ReunionScorer scorer = new ReunionScorer();

    @Test
    @DisplayName("감점이 없으면 BASE(50)에서 시작한다")
    void base_noDeductions() {
        assertThat(scorer.apply(List.of())).isEqualTo(50);
    }

    @Test
    @DisplayName("감점을 BASE에서 빼서 합산한다")
    void sumsDeductions() {
        int score = scorer.apply(List.of(
                Deduction.of("상대가 먼저 통보", 10, "근거"),
                Deduction.of("연락 두절", 5, "근거")));

        assertThat(score).isEqualTo(35); // 50 - 10 - 5
    }

    @Test
    @DisplayName("감점이 커도 하한(5) 아래로는 내려가지 않는다")
    void flooredAtMin() {
        int score = scorer.apply(List.of(Deduction.of("차단", 200, "근거")));

        assertThat(score).isEqualTo(5);
    }

    @Test
    @DisplayName("항목별 상한이 없어 큰 감점 한 방도 그대로 반영된다(하한에서 흡수)")
    void noPerItemCap() {
        int single = scorer.apply(List.of(Deduction.of("치명적 신호", 100, "근거")));
        int split = scorer.apply(List.of(
                Deduction.of("신호1", 50, "근거"),
                Deduction.of("신호2", 50, "근거")));

        assertThat(single).isEqualTo(split).isEqualTo(5);
    }

    @Test
    @DisplayName("가점은 감점을 되살린다 — 바람 30에 재회 의사 15면 35")
    void boostsOffsetDeductions() {
        int score = scorer.apply(List.of(
                Deduction.of("바람", 30, "근거"),
                Deduction.boostOf("상대가 먼저 재회 의사", 15, "근거")));

        assertThat(score).isEqualTo(35); // 50 - 30 + 15
    }

    @Test
    @DisplayName("가점 합산은 상한(20)에서 잘린다 — 가점으로 확신을 만들 수 없다")
    void boostsCappedInTotal() {
        int score = scorer.apply(List.of(
                Deduction.of("바람", 30, "근거"),
                Deduction.boostOf("재회 의사", 20, "근거"),
                Deduction.boostOf("만남 제안", 15, "근거")));

        assertThat(score).isEqualTo(40); // 50 - 30 + min(35, 20)
    }

    @Test
    @DisplayName("가점은 BASE 위로도 올린다 — 합산 상한(20)이 걸려 최대 70")
    void cappedByBoostTotal() {
        int score = scorer.apply(List.of(
                Deduction.boostOf("재회 의사", 20, "근거"),
                Deduction.boostOf("만남 제안", 15, "근거")));

        assertThat(score).isEqualTo(70); // 50 + min(35, 20)
    }
}
