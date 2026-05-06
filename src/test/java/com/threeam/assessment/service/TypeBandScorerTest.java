package com.threeam.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.threeam.assessment.entity.AssessmentFactor;
import com.threeam.assessment.entity.BreakupType;
import com.threeam.assessment.entity.FactorLevel;
import com.threeam.assessment.entity.FactorName;
import com.threeam.assessment.entity.JumpRule;
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
        int score = scorer.apply(BreakupType.IMPULSIVE, JumpRule.NONE, List.of(
                factor(FactorName.PARTNER_SIGNAL, FactorLevel.NEUTRAL)));
        assertThat(score).isEqualTo(67); // 충동형 55~80의 중앙
    }

    @Test
    @DisplayName("5단 판정 — 매우X는 전체 폭, X는 절반 폭으로 움직인다")
    void apply_levelsScaleByStrength() {
        int weak = scorer.apply(BreakupType.BURNOUT, JumpRule.NONE, List.of(
                factor(FactorName.PARTNER_SIGNAL, FactorLevel.FAVORABLE)));    // +5(절반)
        int strongDown = scorer.apply(BreakupType.BURNOUT, JumpRule.NONE, List.of(
                factor(FactorName.USER_CONDUCT, FactorLevel.STRONG_UNFAVORABLE))); // -8(전체)
        assertThat(weak).isEqualTo(30);       // 25 + 5
        assertThat(strongDown).isEqualTo(17); // 25 - 8
    }

    @Test
    @DisplayName("신설 요인 — 관계자산과 접점은 소형(±4/±2)으로 움직인다")
    void apply_newFactors() {
        int score = scorer.apply(BreakupType.BURNOUT, JumpRule.NONE, List.of(
                factor(FactorName.RELATIONSHIP_ASSET, FactorLevel.STRONG_FAVORABLE), // +4
                factor(FactorName.CONTACT_PATH, FactorLevel.FAVORABLE)));            // +2
        assertThat(score).isEqualTo(31); // 25 + 4 + 2
    }

    @Test
    @DisplayName("요인이 쌓여도 유형 대역을 벗어나지 않는다(대역 클램프)")
    void apply_clampsToBand() {
        int score = scorer.apply(BreakupType.TRUST_BROKEN, JumpRule.NONE, List.of(
                factor(FactorName.PARTNER_SIGNAL, FactorLevel.FAVORABLE),
                factor(FactorName.USER_CONDUCT, FactorLevel.STRONG_FAVORABLE),
                factor(FactorName.NOTICE_TONE, FactorLevel.STRONG_FAVORABLE),
                factor(FactorName.PARTNER_PATTERN, FactorLevel.STRONG_FAVORABLE)));
        assertThat(score).isEqualTo(15); // 신뢰붕괴형 상한 — 유리가 쌓여도 5~15를 못 벗어난다
    }

    @Test
    @DisplayName("새 연인 정착만 대역 하한을 뚫고 내려간다")
    void apply_settledReplacementBreachesFloor() {
        int seeing = scorer.apply(BreakupType.FADED, JumpRule.NONE, List.of(
                replacement(FactorLevel.UNFAVORABLE, ReplacementStage.SEEING),
                factor(FactorName.PARTNER_SIGNAL, FactorLevel.STRONG_UNFAVORABLE)));
        int settled = scorer.apply(BreakupType.FADED, JumpRule.NONE, List.of(
                replacement(FactorLevel.UNFAVORABLE, ReplacementStage.SETTLED),
                factor(FactorName.PARTNER_SIGNAL, FactorLevel.STRONG_UNFAVORABLE)));
        assertThat(seeing).isEqualTo(20);  // 권태식음형 하한(20)에서 멈춤
        assertThat(settled).isEqualTo(10); // 정착은 하한 -10까지 (30 -12 -10 = 8 → 하한 10)
    }

    @Test
    @DisplayName("상대신호 '매우유리'는 대역 상한을 뚫고 올라간다 — 강한 반전 신호가 캡에 막히지 않게")
    void apply_strongSignalBreachesCeiling() {
        int strong = scorer.apply(BreakupType.BURNOUT, JumpRule.NONE, List.of(
                factor(FactorName.PARTNER_SIGNAL, FactorLevel.STRONG_FAVORABLE), // +10
                factor(FactorName.CONTACT_PATH, FactorLevel.STRONG_FAVORABLE),   // +4
                factor(FactorName.RELATIONSHIP_ASSET, FactorLevel.STRONG_FAVORABLE))); // +4
        assertThat(strong).isEqualTo(43); // 25 + 18 = 43, 상한 35+10=45 안이라 그대로
    }

    @Test
    @DisplayName("점프 규칙 — 유저통보상대미련과 상대재회의사는 60~85, 반복재회패턴은 55~80 대역")
    void apply_jumpBands() {
        int hint = scorer.apply(BreakupType.BURNOUT, JumpRule.PARTNER_HINT, List.of());
        int cycle = scorer.apply(BreakupType.TRUST_BROKEN, JumpRule.REPEAT_CYCLE, List.of());
        assertThat(hint).isEqualTo(72);  // (60+85)/2 — 유형 대역과 무관
        assertThat(cycle).isEqualTo(67); // (55+80)/2 — 최악 유형이어도 패턴이 입증된 판
    }

    @Test
    @DisplayName("절대 상한 95 — 점프 대역 + 상한 돌파가 겹쳐도 96~100(제안 확정 몫)에 닿지 않는다")
    void apply_absoluteCeiling() {
        int score = scorer.apply(BreakupType.IMPULSIVE, JumpRule.PARTNER_HINT, List.of(
                factor(FactorName.PARTNER_SIGNAL, FactorLevel.STRONG_FAVORABLE),
                replacement(FactorLevel.STRONG_FAVORABLE, null),
                factor(FactorName.USER_CONDUCT, FactorLevel.STRONG_FAVORABLE),
                factor(FactorName.NOTICE_TONE, FactorLevel.STRONG_FAVORABLE),
                factor(FactorName.PARTNER_PATTERN, FactorLevel.STRONG_FAVORABLE),
                factor(FactorName.RELATIONSHIP_ASSET, FactorLevel.STRONG_FAVORABLE),
                factor(FactorName.CONTACT_PATH, FactorLevel.STRONG_FAVORABLE)));
        // 72 + 41 = 113 → 상한 85+10=95 → 절대 상한 95
        assertThat(score).isEqualTo(95);
    }
}
