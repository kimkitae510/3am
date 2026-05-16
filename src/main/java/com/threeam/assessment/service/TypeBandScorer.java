package com.threeam.assessment.service;

import com.threeam.assessment.entity.AssessmentFactor;
import com.threeam.assessment.entity.BreakupType;
import com.threeam.assessment.entity.FactorLevel;
import com.threeam.assessment.entity.FactorName;
import com.threeam.assessment.entity.JumpRule;
import com.threeam.assessment.entity.ReplacementStage;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

// 확률 계산 v2: 유형이 대역을 정하고(1층), 요인 판정이 대역 안에서 조정한다(2층).
// LLM은 유형과 요인의 '판정'만 내리고 숫자는 전부 여기 상수다 — 같은 판정이면 언제나 같은 확률.
// (v1 ReunionScorer는 LLM이 신호마다 점수를 발명해 같은 사연에도 확률이 출렁였다.)
// 대역과 폭의 근거는 루브릭 문서(루브릭v2.md) — 커뮤니티/업계 조사 기반 서열에 우리 설계값.
@Component
public class TypeBandScorer {

    private static final int ABS_MIN = 3;
    private static final int ABS_MAX = 95; // 96~100은 상대의 유효한 재회 제안(확정 100) 몫

    // 점프 대역 — 유형 대역을 통째로 교체한다.
    // USER_DUMPED, PARTNER_HINT 60~85: 상대의 마음이 열려 있음이 확인된 판.
    // REPEAT_CYCLE 55~80: 같은 사유로 헤어지고도 매번 돌아온 관계 — 패턴이 입증됨(충동형급).
    // USER_DUMPED_FAINT 상한 75: 흔적 신호들 위에 상대가 먼저 알려오기까지 한 판이 65에
    // 눌리던 것 완화 — 뚜렷(85)과의 낙차도 줄어 경계 오판의 비용이 작아진다.
    // PARTNER_RECONTACT 30~50: 문은 다시 열렸지만 내용은 안부뿐 — 반반(50)을 못 넘는 게 정직하다.
    private static final Map<JumpRule, Band> JUMP_BANDS = new EnumMap<>(Map.of(
            JumpRule.USER_DUMPED, new Band(60, 85),
            JumpRule.USER_DUMPED_FAINT, new Band(40, 75),
            JumpRule.USER_DUMPED_NONE, new Band(12, 30),
            JumpRule.PARTNER_RECONTACT, new Band(30, 50),
            JumpRule.PARTNER_HINT, new Band(60, 85),
            JumpRule.REPEAT_CYCLE, new Band(55, 80)));

    private static final Map<BreakupType, Band> BANDS = new EnumMap<>(Map.of(
            BreakupType.IMPULSIVE, new Band(55, 80),
            BreakupType.SITUATIONAL, new Band(50, 75),
            BreakupType.EXTERNAL, new Band(30, 50),
            BreakupType.FADED, new Band(20, 40),
            BreakupType.BURNOUT, new Band(15, 35),
            BreakupType.RESOLVED, new Band(10, 25),
            BreakupType.TRANSFER, new Band(10, 30),
            BreakupType.TRUST_BROKEN, new Band(5, 15)));

    // 요인별 조정 폭(매우X = 전체 폭, X = 절반). 선언 순서가 무게 순서와 일치한다.
    private static final Map<FactorName, Integer> WIDTHS = new EnumMap<>(Map.of(
            FactorName.PARTNER_SIGNAL, 10,
            FactorName.REPLACEMENT, 4,   // 유리(부재 확인)일 때만 이 값. 불리는 stage가 정한다
            FactorName.USER_CONDUCT, 8,
            FactorName.NOTICE_TONE, 6,
            FactorName.PARTNER_PATTERN, 5,
            FactorName.RELATIONSHIP_ASSET, 4,
            FactorName.CONTACT_PATH, 4));

    // 대체자 불리의 세분 폭. 정착은 대역 하한을 뚫는다(아래 clamp에서 허용).
    private static final int REPLACEMENT_SEEING = 6;
    private static final int REPLACEMENT_SETTLED = 12;
    private static final int SETTLED_FLOOR_BREACH = 10;

    // 상대신호 '매우유리'(만남에서의 적극적 미련, 반복되는 능동 신호)는 대역 상한을 뚫는다 —
    // 정착이 하한을 뚫는 것과 대칭. 상한이 강한 반전 신호를 막던 실측(소진형 35 캡) 대응.
    private static final int STRONG_SIGNAL_CEILING_BREACH = 10;

    public int apply(BreakupType type, JumpRule jumpRule, List<AssessmentFactor> factors) {
        Band band = JUMP_BANDS.getOrDefault(jumpRule == null ? JumpRule.NONE : jumpRule,
                BANDS.get(type));
        int score = (band.lo() + band.hi()) / 2;
        boolean settled = false;
        boolean strongSignal = false;
        for (AssessmentFactor factor : factors) {
            score += delta(factor);
            settled = settled || isSettled(factor);
            strongSignal = strongSignal || (factor.getName() == FactorName.PARTNER_SIGNAL
                    && factor.getLevel() == FactorLevel.STRONG_FAVORABLE);
        }
        // 대역 클램프 — 요인이 쌓여도 유형이 정한 구간을 벗어나지 않는다. 예외 둘(대칭):
        // 새 연인 '정착'은 하한을, 상대신호 '매우유리'는 상한을 뚫는다.
        int lo = settled ? band.lo() - SETTLED_FLOOR_BREACH : band.lo();
        int hi = strongSignal ? band.hi() + STRONG_SIGNAL_CEILING_BREACH : band.hi();
        score = Math.max(lo, Math.min(hi, score));
        return Math.max(ABS_MIN, Math.min(ABS_MAX, score));
    }

    private int delta(AssessmentFactor factor) {
        FactorLevel level = factor.getLevel();
        if (level == FactorLevel.NEUTRAL) {
            return 0;
        }
        if (factor.getName() == FactorName.REPLACEMENT && level.unfavorableSide()) {
            return isSettled(factor) ? -REPLACEMENT_SETTLED : -REPLACEMENT_SEEING;
        }
        int width = WIDTHS.get(factor.getName());
        int size = (level == FactorLevel.STRONG_FAVORABLE || level == FactorLevel.STRONG_UNFAVORABLE)
                ? width
                : Math.max(1, width / 2);
        return level.favorableSide() ? size : -size;
    }

    private boolean isSettled(AssessmentFactor factor) {
        return factor.getName() == FactorName.REPLACEMENT
                && factor.getLevel().unfavorableSide()
                && factor.getStage() == ReplacementStage.SETTLED;
    }

    private record Band(int lo, int hi) {
    }
}
