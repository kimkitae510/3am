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
        assertThat(score).isEqualTo(67); // 충동형 60~75의 중앙
    }

    @Test
    @DisplayName("5단 판정 — 매우X는 전체 폭, X는 절반 폭으로 움직인다")
    void apply_levelsScaleByStrength() {
        // 좁아진 대역에선 전폭 이동이 하한에 닿기 쉬워, 클램프 없이 폭이 보이는 상황형(52~68)으로 잰다.
        int weak = scorer.apply(BreakupType.SITUATIONAL, JumpRule.NONE, List.of(
                factor(FactorName.PARTNER_SIGNAL, FactorLevel.FAVORABLE)));    // +5(절반)
        int strongDown = scorer.apply(BreakupType.SITUATIONAL, JumpRule.NONE, List.of(
                factor(FactorName.USER_CONDUCT, FactorLevel.STRONG_UNFAVORABLE))); // -8(전체)
        assertThat(weak).isEqualTo(65);       // 60 + 5
        assertThat(strongDown).isEqualTo(52); // 60 - 8 (하한과 정확히 만남)
    }

    @Test
    @DisplayName("신설 요인 — 관계자산과 접점은 소형(±4/±2)으로 움직인다")
    void apply_newFactors() {
        int score = scorer.apply(BreakupType.BURNOUT, JumpRule.NONE, List.of(
                factor(FactorName.RELATIONSHIP_ASSET, FactorLevel.STRONG_FAVORABLE), // +4
                factor(FactorName.CONTACT_PATH, FactorLevel.FAVORABLE)));            // +2
        assertThat(score).isEqualTo(30); // 24 + 4 + 2
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
        assertThat(seeing).isEqualTo(22);  // 권태식음형 하한(22)에서 멈춤
        assertThat(settled).isEqualTo(12); // 정착은 하한 -10까지 (28 -12 -10 = 6 → 하한 12)
    }

    @Test
    @DisplayName("상대신호 '매우유리'는 대역 상한을 뚫고 올라간다 — 강한 반전 신호가 캡에 막히지 않게")
    void apply_strongSignalBreachesCeiling() {
        int strong = scorer.apply(BreakupType.BURNOUT, JumpRule.NONE, List.of(
                factor(FactorName.PARTNER_SIGNAL, FactorLevel.STRONG_FAVORABLE), // +10
                factor(FactorName.CONTACT_PATH, FactorLevel.STRONG_FAVORABLE),   // +4
                factor(FactorName.RELATIONSHIP_ASSET, FactorLevel.STRONG_FAVORABLE))); // +4
        assertThat(strong).isEqualTo(40); // 24 + 18 = 42 → 상한 30+10=40 클램프
    }

    @Test
    @DisplayName("점프 규칙 — 유저통보상대미련과 상대재회의사는 65~80, 반복재회패턴은 58~72 대역")
    void apply_jumpBands() {
        int hint = scorer.apply(BreakupType.BURNOUT, JumpRule.PARTNER_HINT, List.of());
        int cycle = scorer.apply(BreakupType.TRUST_BROKEN, JumpRule.REPEAT_CYCLE, List.of());
        assertThat(hint).isEqualTo(72);  // (65+80)/2 — 유형 대역과 무관
        assertThat(cycle).isEqualTo(65); // (58+72)/2 — 최악 유형이어도 패턴이 입증된 판
    }

    @Test
    @DisplayName("상대접촉재개 — 낮은 유형 대역을 벗어나 30~50에서 계산된다")
    void apply_partnerRecontactBand() {
        int base = scorer.apply(BreakupType.TRUST_BROKEN, JumpRule.PARTNER_RECONTACT, List.of());
        int favorable = scorer.apply(BreakupType.TRUST_BROKEN, JumpRule.PARTNER_RECONTACT, List.of(
                factor(FactorName.PARTNER_SIGNAL, FactorLevel.FAVORABLE),        // +5
                factor(FactorName.USER_CONDUCT, FactorLevel.STRONG_FAVORABLE))); // +8
        assertThat(base).isEqualTo(37);      // (30+45)/2 — 신뢰붕괴(5~15)여도 문이 다시 열린 판
        assertThat(favorable).isEqualTo(45); // 37 + 13 = 50 → 상한 45 클램프(내용은 아직 안부)
    }

    @Test
    @DisplayName("유저통보미련흔적 — 상한 70까지 열린다(흔적 신호 + 먼저 온 연락이 눌리지 않게)")
    void apply_faintCeiling() {
        int score = scorer.apply(null, JumpRule.USER_DUMPED_FAINT, List.of(
                factor(FactorName.PARTNER_SIGNAL, FactorLevel.FAVORABLE),       // +5
                factor(FactorName.NOTICE_TONE, FactorLevel.STRONG_FAVORABLE),   // +6
                factor(FactorName.RELATIONSHIP_ASSET, FactorLevel.STRONG_FAVORABLE), // +4
                factor(FactorName.USER_CONDUCT, FactorLevel.STRONG_FAVORABLE))); // +8
        // (50+70)/2=60, +23 = 83 → 상한 70 클램프 — 뚜렷(80)과의 낙차 10, 매우유리 관통이면 80
        assertThat(score).isEqualTo(70);
    }

    @Test
    @DisplayName("하향 점프 — 문닫힘과 결혼약혼은 유리한 유형 대역을 이기고 바닥 근처로 내린다")
    void apply_downwardJumps() {
        // 충동형(유리한 유형)이어도 상대가 정리를 요구하고 문을 닫았으면 그 사실이 대역을 이긴다.
        int closed = scorer.apply(BreakupType.IMPULSIVE, JumpRule.PARTNER_CLOSED, List.of());
        int married = scorer.apply(BreakupType.IMPULSIVE, JumpRule.PARTNER_MARRIED, List.of());
        assertThat(closed).isEqualTo(13); // (8+18)/2
        assertThat(married).isEqualTo(5); // (3+8)/2
    }

    @Test
    @DisplayName("최상단 — 최고 점프에 관통까지 겹쳐도 90, 96~100(제안 확정 몫)에 닿지 않는다")
    void apply_absoluteCeiling() {
        int score = scorer.apply(BreakupType.IMPULSIVE, JumpRule.PARTNER_HINT, List.of(
                factor(FactorName.PARTNER_SIGNAL, FactorLevel.STRONG_FAVORABLE),
                replacement(FactorLevel.STRONG_FAVORABLE, null),
                factor(FactorName.USER_CONDUCT, FactorLevel.STRONG_FAVORABLE),
                factor(FactorName.NOTICE_TONE, FactorLevel.STRONG_FAVORABLE),
                factor(FactorName.PARTNER_PATTERN, FactorLevel.STRONG_FAVORABLE),
                factor(FactorName.RELATIONSHIP_ASSET, FactorLevel.STRONG_FAVORABLE),
                factor(FactorName.CONTACT_PATH, FactorLevel.STRONG_FAVORABLE)));
        // 72 + 41 = 113 → 상한 80+10=90. 절대 상한 95는 그 위의 최후 방어선으로 남는다
        assertThat(score).isEqualTo(90);
    }
}
