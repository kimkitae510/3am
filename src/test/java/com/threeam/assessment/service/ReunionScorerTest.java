package com.threeam.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.threeam.assessment.dto.AssessmentRequest;
import com.threeam.assessment.entity.BreakupReason;
import com.threeam.assessment.entity.BreakupType;
import com.threeam.assessment.entity.ContactStatus;
import com.threeam.assessment.entity.Initiator;
import com.threeam.assessment.entity.PartnerType;
import com.threeam.assessment.entity.ReunionVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ReunionScorerTest {

    private final ReunionScorer scorer = new ReunionScorer();

    @Test
    @DisplayName("졸업 판정 - 상대가 새 사람을 만나면 확률 없이 LET_GO")
    void letGo_partnerNewPerson() {
        ReunionScore result = scorer.score(request(Initiator.ME, ContactStatus.PARTNER_CONTACTED,
                BreakupReason.EXTERNAL, true, 30, false, 10));

        assertThat(result.verdict()).isEqualTo(ReunionVerdict.LET_GO);
        assertThat(result.probability()).isNull(); // 숫자를 주지 않는다
    }

    @Test
    @DisplayName("졸업 판정 - 바람/신뢰 붕괴면 확률 없이 LET_GO")
    void letGo_cheating() {
        ReunionScore result = scorer.score(request(Initiator.PARTNER, ContactStatus.NONE,
                BreakupReason.CHEATING, false, 12, false, 20));

        assertThat(result.verdict()).isEqualTo(ReunionVerdict.LET_GO);
        assertThat(result.probability()).isNull();
    }

    @Test
    @DisplayName("부정 신호가 겹치면 확률이 바닥(하한)까지 처박힌다")
    void negativeSignals_flooredLow() {
        // 상대가 참, 차단, 성격차, 재회 실패 이력, 오래 경과 → 크게 깎임
        ReunionScore result = scorer.score(request(Initiator.PARTNER, ContactStatus.BLOCKED,
                BreakupReason.PERSONALITY, false, 6, true, 120));

        assertThat(result.verdict()).isEqualTo(ReunionVerdict.POSSIBLE);
        assertThat(result.probability()).isEqualTo(5); // 하한
    }

    @Test
    @DisplayName("긍정 신호가 다 겹쳐도 상한(70)을 넘지 않는다 - 거짓 희망 금지")
    void positiveSignals_cappedAtMax() {
        // 내가 참, 상대 먼저 연락, 외부요인, 장기연애 → 최상 조건
        ReunionScore result = scorer.score(request(Initiator.ME, ContactStatus.PARTNER_CONTACTED,
                BreakupReason.EXTERNAL, false, 36, false, 5));

        assertThat(result.probability()).isLessThanOrEqualTo(70);
    }

    @Test
    @DisplayName("유형 분류 - 매달리면 매달림형, 상대 차단이면 단호 손절형")
    void classifyTypes() {
        ReunionScore result = scorer.score(request(Initiator.PARTNER, ContactStatus.I_CLING,
                BreakupReason.PERSONALITY, false, 12, false, 30));

        assertThat(result.breakupType()).isEqualTo(BreakupType.CLINGER);

        ReunionScore blocked = scorer.score(request(Initiator.PARTNER, ContactStatus.BLOCKED,
                BreakupReason.PERSONALITY, false, 12, false, 30));
        assertThat(blocked.partnerType()).isEqualTo(PartnerType.DECISIVE);
    }

    private AssessmentRequest request(Initiator whoEnded, ContactStatus contactStatus,
                                      BreakupReason breakupReason, boolean partnerNewPerson,
                                      int relationshipMonths, boolean pastReunionFailed, int daysSinceBreakup) {
        AssessmentRequest r = new AssessmentRequest();
        ReflectionTestUtils.setField(r, "whoEnded", whoEnded);
        ReflectionTestUtils.setField(r, "contactStatus", contactStatus);
        ReflectionTestUtils.setField(r, "breakupReason", breakupReason);
        ReflectionTestUtils.setField(r, "partnerNewPerson", partnerNewPerson);
        ReflectionTestUtils.setField(r, "relationshipMonths", relationshipMonths);
        ReflectionTestUtils.setField(r, "pastReunionFailed", pastReunionFailed);
        ReflectionTestUtils.setField(r, "daysSinceBreakup", daysSinceBreakup);
        return r;
    }
}
