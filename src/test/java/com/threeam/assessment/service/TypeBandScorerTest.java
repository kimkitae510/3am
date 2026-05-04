package com.threeam.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.threeam.assessment.entity.AssessmentFactor;
import com.threeam.assessment.entity.BreakupType;
import com.threeam.assessment.entity.FactorLevel;
import com.threeam.assessment.entity.FactorName;
import com.threeam.assessment.entity.ReplacementStage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TypeBandScorerTest {

    private final TypeBandScorer scorer = new TypeBandScorer();

    private AssessmentFactor factor(FactorName name, FactorLevel level) {
        return AssessmentFactor.of(name, level, "근거", null, null);
    }

    private AssessmentFactor replacement(FactorLevel level, ReplacementStage stage) {
        return AssessmentFactor.of(FactorName.REPLACEMENT, level, "근거", null, stage);
    }

    @Test
    @DisplayName("요인이 전부 중립이면 유형 대역의 중앙값이 나온다")
    void apply_neutralGivesBandCenter() {
        int score = scorer.apply(BreakupType.IMPULSIVE, false, List.of(
                factor(FactorName.PARTNER_SIGNAL, FactorLevel.NEUTRAL)));
        assertThat(score).isEqualTo(67); // 충동형 55~80의 중앙 (55+80)/2
    }

    @Test
    @DisplayName("유리/불리 판정이 요인별 상수만큼 움직인다")
    void apply_factorsShiftByWidth() {
        int score = scorer.apply(BreakupType.BURNOUT, false, List.of(
                factor(FactorName.PARTNER_SIGNAL, FactorLevel.FAVORABLE),   // +10
                factor(FactorName.USER_CONDUCT, FactorLevel.UNFAVORABLE))); // -8
        assertThat(score).isEqualTo(27); // 소진형 중앙 25 + 10 - 8
    }

    @Test
    @DisplayName("요인이 쌓여도 유형 대역을 벗어나지 않는다(대역 클램프)")
    void apply_clampsToBand() {
        int score = scorer.apply(BreakupType.TRUST_BROKEN, false, List.of(
                factor(FactorName.PARTNER_SIGNAL, FactorLevel.FAVORABLE),
                factor(FactorName.USER_CONDUCT, FactorLevel.FAVORABLE),
                factor(FactorName.NOTICE_TONE, FactorLevel.FAVORABLE),
                factor(FactorName.PARTNER_PATTERN, FactorLevel.FAVORABLE)));
        assertThat(score).isEqualTo(15); // 신뢰붕괴형 상한 — 유리가 쌓여도 5~15를 못 벗어난다
    }

    @Test
    @DisplayName("새 연인 정착만 대역 하한을 뚫고 내려간다")
    void apply_settledReplacementBreachesFloor() {
        int seeing = scorer.apply(BreakupType.FADED, false, List.of(
                replacement(FactorLevel.UNFAVORABLE, ReplacementStage.SEEING),
                factor(FactorName.PARTNER_SIGNAL, FactorLevel.UNFAVORABLE)));
        int settled = scorer.apply(BreakupType.FADED, false, List.of(
                replacement(FactorLevel.UNFAVORABLE, ReplacementStage.SETTLED),
                factor(FactorName.PARTNER_SIGNAL, FactorLevel.UNFAVORABLE)));
        assertThat(seeing).isEqualTo(20);  // 권태식음형 하한(20)에서 멈춤 (30 -6 -10 → 클램프)
        assertThat(settled).isEqualTo(10); // 정착은 하한 -10까지 허용 (30 -12 -10 = 8 → 하한 10)
    }

    @Test
    @DisplayName("점프 규칙(유저 통보 + 상대 미련)은 유형 대역 대신 60~85를 쓴다")
    void apply_jumpBandOverridesType() {
        int score = scorer.apply(BreakupType.BURNOUT, true, List.of(
                factor(FactorName.PARTNER_SIGNAL, FactorLevel.FAVORABLE)));
        assertThat(score).isEqualTo(82); // (60+85)/2 + 10 = 82 — 소진형 대역과 무관
    }

    @Test
    @DisplayName("절대 상한 95 — 점프 대역에서 유리가 쌓여도 96~100(제안 확정 몫)에 닿지 않는다")
    void apply_absoluteCeiling() {
        int score = scorer.apply(BreakupType.IMPULSIVE, true, List.of(
                factor(FactorName.PARTNER_SIGNAL, FactorLevel.FAVORABLE),
                replacement(FactorLevel.FAVORABLE, null),
                factor(FactorName.USER_CONDUCT, FactorLevel.FAVORABLE),
                factor(FactorName.NOTICE_TONE, FactorLevel.FAVORABLE),
                factor(FactorName.PARTNER_PATTERN, FactorLevel.FAVORABLE)));
        assertThat(score).isEqualTo(85); // 점프 대역 상한에서 멈춤 — 95 절대 상한보다 먼저 걸린다
    }
}
