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
    @DisplayName("감점이 커도 하한(3) 아래로는 내려가지 않는다")
    void flooredAtMin() {
        int score = scorer.apply(List.of(Deduction.of("차단", 200, "근거")));

        assertThat(score).isEqualTo(3);
    }

    @Test
    @DisplayName("항목별 상한이 없어 큰 감점 한 방도 그대로 반영된다(하한에서 흡수)")
    void noPerItemCap() {
        int single = scorer.apply(List.of(Deduction.of("치명적 신호", 100, "근거")));
        int split = scorer.apply(List.of(
                Deduction.of("신호1", 50, "근거"),
                Deduction.of("신호2", 50, "근거")));

        assertThat(single).isEqualTo(split).isEqualTo(3);
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
    @DisplayName("가점 상한 폐지 — 지속 장벽만 남았으면 가점이 그 장벽만큼만 상쇄한다")
    void boostsUncappedButBarrierPersists() {
        int score = scorer.apply(List.of(
                Deduction.of("바람", 40, "근거"),
                Deduction.boostOf("재회 의사", 20, "근거"),
                Deduction.boostOf("만남 제안", 10, "근거")));

        assertThat(score).isEqualTo(40); // 50 - 40 + 30 — 바람(지속 장벽)이 아직 누른다
    }

    @Test
    @DisplayName("마음 축 감점이 없으면(루브릭이 스테일 감점을 안 냄) 강한 가점이 확 되살린다")
    void boostsFlipWhenNoBarrier() {
        int score = scorer.apply(List.of(
                Deduction.boostOf("먼저 연락 옴", 20, "근거"),
                Deduction.boostOf("능동적 간접 신호", 12, "근거")));

        assertThat(score).isEqualTo(82); // 50 + 32 — 뒤집힘이 상한 20에 안 막힌다
    }

    @Test
    @DisplayName("확률 상한은 95 — 96~100은 유효한 재회 제안(확정 100) 몫")
    void cappedAtMax() {
        int score = scorer.apply(List.of(
                Deduction.boostOf("먼저 재회 의사", 20, "근거"),
                Deduction.boostOf("만남 제안", 20, "근거"),
                Deduction.boostOf("능동 신호", 20, "근거")));

        assertThat(score).isEqualTo(95); // 50 + 60 → 95로 클램프
    }
}
