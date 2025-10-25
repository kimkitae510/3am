package com.threeam.assessment.service;

import com.threeam.assessment.entity.BreakupType;
import com.threeam.assessment.entity.PartnerType;
import com.threeam.assessment.entity.ReunionVerdict;

// 스코어링 결과 홀더(영속화 전). probability는 졸업 판정일 때 null.
public record ReunionScore(
        ReunionVerdict verdict,
        Integer probability,
        BreakupType breakupType,
        PartnerType partnerType,
        String reason) {
}
