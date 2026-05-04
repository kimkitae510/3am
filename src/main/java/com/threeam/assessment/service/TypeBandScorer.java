package com.threeam.assessment.service;

import com.threeam.assessment.entity.AssessmentFactor;
import com.threeam.assessment.entity.BreakupType;
import com.threeam.assessment.entity.FactorLevel;
import com.threeam.assessment.entity.FactorName;
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

    // 점프 대역: 유저가 통보했고 상대에게 미련이 남은 판 — 유형 무관 최상 대역.
    // 찬 쪽의 복귀는 "돌아올 확률"이 아니라 "받아줄 확률"이라 문이 열려 있다.
    private static final Band JUMP = new Band(60, 85);

    private static final Map<BreakupType, Band> BANDS = new EnumMap<>(Map.of(
            BreakupType.IMPULSIVE, new Band(55, 80),
            BreakupType.SITUATIONAL, new Band(50, 75),
            BreakupType.EXTERNAL, new Band(30, 50),
            BreakupType.FADED, new Band(20, 40),
            BreakupType.BURNOUT, new Band(15, 35),
            BreakupType.RESOLVED, new Band(10, 25),
            BreakupType.TRANSFER, new Band(10, 30),
            BreakupType.TRUST_BROKEN, new Band(5, 15)));

    // 요인별 조정 폭. 순서(FactorName 선언 순)가 무게 순서와 일치한다.
    private static final Map<FactorName, Integer> WIDTHS = new EnumMap<>(Map.of(
            FactorName.PARTNER_SIGNAL, 10,
            FactorName.REPLACEMENT, 4,   // 유리(부재 확인)일 때만 이 값. 불리는 stage가 정한다
            FactorName.USER_CONDUCT, 8,
            FactorName.NOTICE_TONE, 6,
            FactorName.PARTNER_PATTERN, 5));

    // 대체자 불리의 세분 폭. 정착은 대역 하한을 뚫는다(아래 clamp에서 허용).
    private static final int REPLACEMENT_SEEING = 6;
    private static final int REPLACEMENT_SETTLED = 12;
    private static final int SETTLED_FLOOR_BREACH = 10;

    public int apply(BreakupType type, boolean userDumpedPartnerLingering,
                     List<AssessmentFactor> factors) {
        Band band = userDumpedPartnerLingering ? JUMP : BANDS.get(type);
        int score = (band.lo() + band.hi()) / 2;
        boolean settled = false;
        for (AssessmentFactor factor : factors) {
            score += delta(factor);
            settled = settled || isSettled(factor);
        }
        // 대역 클램프 — 요인이 아무리 쌓여도 유형이 정한 구간을 벗어나지 않는다.
        // 예외 하나: 새 연인 '정착'은 하한을 뚫는다(대체자가 실질 마감선이라는 조사 결론).
        int lo = settled ? band.lo() - SETTLED_FLOOR_BREACH : band.lo();
        score = Math.max(lo, Math.min(band.hi(), score));
        return Math.max(ABS_MIN, Math.min(ABS_MAX, score));
    }

    private int delta(AssessmentFactor factor) {
        if (factor.getLevel() == FactorLevel.NEUTRAL) {
            return 0;
        }
        boolean favorable = factor.getLevel() == FactorLevel.FAVORABLE;
        if (factor.getName() == FactorName.REPLACEMENT && !favorable) {
            return isSettled(factor) ? -REPLACEMENT_SETTLED : -REPLACEMENT_SEEING;
        }
        int width = WIDTHS.get(factor.getName());
        return favorable ? width : -width;
    }

    private boolean isSettled(AssessmentFactor factor) {
        return factor.getName() == FactorName.REPLACEMENT
                && factor.getLevel() == FactorLevel.UNFAVORABLE
                && factor.getStage() == ReplacementStage.SETTLED;
    }

    private record Band(int lo, int hi) {
    }
}
