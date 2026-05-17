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

    // 대역은 앵커지 칸막이가 아니다 — 폭을 10~16으로 줄였다(구 20~35). 넓은 대역은 유형
    // 구분을 무디게 하고 경계 오판 한 번의 낙차를 키웠다. 0~100을 꽉 채울 이유도 없다:
    // 극단은 관통 규칙(±10)과 절대 3~95가 맡고, 대역 안 변별은 요인이 한다.
    // 점프 대역 — 유형 대역을 통째로 교체한다.
    // USER_DUMPED, PARTNER_HINT 65~80: 상대의 마음이 열려 있음이 확인된 판.
    // REPEAT_CYCLE 58~72: 같은 사유로 헤어지고도 매번 돌아온 관계 — 패턴이 입증됨(충동형급).
    // USER_DUMPED_FAINT 50~70: 흔적 위에 먼저 온 정리 선언까지 겹친 판이 눌리지 않게
    // 상단을 열어둠 — 뚜렷(80)과의 낙차 10. 매우유리 관통이면 80까지.
    // PARTNER_RECONTACT 30~45: 문은 다시 열렸지만 내용은 안부뿐 — 반반을 못 넘는 게 정직하다.
    // PARTNER_CLOSED, PARTNER_MARRIED: 유일한 하향 점프 — 정리 요구+차단 유지는 유리한 유형
    // 대역(충동형 하한 등)을 이기고, 결혼/약혼은 정착(-12)보다 위의 종결 신호라 바닥 근처로.
    private static final Map<JumpRule, Band> JUMP_BANDS = new EnumMap<>(Map.of(
            JumpRule.USER_DUMPED, new Band(65, 80),
            JumpRule.USER_DUMPED_FAINT, new Band(50, 70),
            JumpRule.USER_DUMPED_NONE, new Band(12, 24),
            JumpRule.PARTNER_RECONTACT, new Band(30, 45),
            JumpRule.PARTNER_HINT, new Band(65, 80),
            JumpRule.REPEAT_CYCLE, new Band(58, 72),
            JumpRule.PARTNER_CLOSED, new Band(8, 18),
            JumpRule.PARTNER_MARRIED, new Band(3, 8)));

    private static final Map<BreakupType, Band> BANDS = new EnumMap<>(Map.of(
            BreakupType.IMPULSIVE, new Band(60, 75),
            BreakupType.SITUATIONAL, new Band(52, 68),
            BreakupType.EXTERNAL, new Band(35, 48),
            BreakupType.FADED, new Band(22, 35),
            BreakupType.BURNOUT, new Band(18, 30),
            BreakupType.RESOLVED, new Band(10, 22),
            BreakupType.TRANSFER, new Band(12, 24),
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
