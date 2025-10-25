package com.threeam.assessment.service;

import com.threeam.assessment.dto.AssessmentRequest;
import com.threeam.assessment.dto.BreakupReason;
import com.threeam.assessment.dto.ContactStatus;
import com.threeam.assessment.dto.Initiator;
import com.threeam.assessment.entity.BreakupType;
import com.threeam.assessment.entity.PartnerType;
import com.threeam.assessment.entity.ReunionVerdict;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

// 재회 확률을 LLM이 아니라 결정론적 룰로 계산한다.
// 의도적으로 보수적: 기준점 낮게, 긍정 신호는 캡, 부정 신호는 크게 깎는다. "거짓 희망 금지."
@Component
public class ReunionScorer {

    private static final int BASE = 25;   // 재회는 0이 아니라 -에서 시작한다는 전제
    private static final int MIN = 5;
    private static final int MAX = 70;    // 아무리 좋아도 상한 → 헛된 확신 차단

    public ReunionScore score(AssessmentRequest r) {
        BreakupType breakupType = classifyMine(r);
        PartnerType partnerType = classifyPartner(r);

        // 졸업 판정: 새 사람 or 신뢰 붕괴는 숫자 대신 놓아주라는 판정.
        if (r.isPartnerNewPerson()) {
            return new ReunionScore(ReunionVerdict.LET_GO, null, breakupType, partnerType,
                    "상대가 이미 새 사람을 만나고 있어. 미안하지만 이건 놓아줄 때야.");
        }
        if (r.getBreakupReason() == BreakupReason.CHEATING) {
            return new ReunionScore(ReunionVerdict.LET_GO, null, breakupType, partnerType,
                    "신뢰가 무너진 이별이야. 다시 만나도 그 상처는 안 없어져.");
        }

        int score = BASE;
        List<String> reasons = new ArrayList<>();

        switch (r.getWhoEnded()) {
            case PARTNER -> { score -= 15; reasons.add("상대가 먼저 이별을 말했어 (−)"); }
            case ME -> { score += 10; reasons.add("네가 먼저 정리한 쪽이라 여지는 있어 (+)"); }
            case MUTUAL -> { score -= 5; reasons.add("합의 이별이라 재회 동력이 약해 (−)"); }
            default -> { }
        }
        switch (r.getContactStatus()) {
            case PARTNER_CONTACTED -> { score += 15; reasons.add("상대가 먼저 연락 온 건 큰 플러스 (+)"); }
            case NONE -> { score -= 5; reasons.add("서로 연락이 끊긴 상태 (−)"); }
            case I_CLING -> { score -= 15; reasons.add("네가 계속 매달리는 건 오히려 마이너스 (−)"); }
            case IGNORED -> { score -= 20; reasons.add("읽씹당하는 중이라 크게 깎였어 (−)"); }
            case BLOCKED -> { score -= 30; reasons.add("차단은 강한 거절 신호야 (−−)"); }
            default -> { }
        }
        switch (r.getBreakupReason()) {
            case EXTERNAL -> { score += 10; reasons.add("외부 요인 이별이라 풀릴 여지가 있어 (+)"); }
            case BOREDOM -> { score -= 15; reasons.add("권태로 식은 감정은 되돌리기 어려워 (−)"); }
            case PERSONALITY -> { score -= 20; reasons.add("성격 차이는 안 바뀌면 또 반복돼 (−)"); }
            default -> { }
        }
        if (r.getRelationshipMonths() >= 24) {
            score += 5;
            reasons.add("오래 만난 정은 무시 못 해 (+)");
        } else if (r.getRelationshipMonths() < 3) {
            score -= 5;
            reasons.add("만난 기간이 짧아 유대가 약해 (−)");
        }
        if (r.isPastReunionFailed()) {
            score -= 15;
            reasons.add("이미 재회 실패 이력이 있어 (−)");
        }
        if (r.getDaysSinceBreakup() > 90) {
            score -= 10;
            reasons.add("이별한 지 오래돼 감정이 정리됐을 확률이 높아 (−)");
        }

        int clamped = Math.max(MIN, Math.min(MAX, score));
        return new ReunionScore(ReunionVerdict.POSSIBLE, clamped, breakupType, partnerType,
                String.join("\n", reasons));
    }

    private BreakupType classifyMine(AssessmentRequest r) {
        if (r.getContactStatus() == ContactStatus.I_CLING) {
            return BreakupType.CLINGER;
        }
        if (r.getWhoEnded() == Initiator.ME) {
            return BreakupType.REGRETTER;
        }
        return BreakupType.SELF_BLAMER;
    }

    private PartnerType classifyPartner(AssessmentRequest r) {
        if (r.isPartnerNewPerson() || r.getContactStatus() == ContactStatus.BLOCKED) {
            return PartnerType.DECISIVE;
        }
        if (r.getContactStatus() == ContactStatus.PARTNER_CONTACTED) {
            return PartnerType.AMBIVALENT;
        }
        return PartnerType.COLD;
    }
}
