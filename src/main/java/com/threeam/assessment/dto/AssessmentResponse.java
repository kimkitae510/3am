package com.threeam.assessment.dto;

import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.ReunionVerdict;
import java.time.LocalDateTime;
import lombok.Getter;

@Getter
public class AssessmentResponse {

    private final ReunionVerdict verdict;
    private final Integer probability;   // 졸업 판정이면 null
    private final String myBreakupType;  // 라벨(한국어)
    private final String partnerType;    // 라벨(한국어)
    private final String reason;
    private final LocalDateTime createdAt;

    private AssessmentResponse(ReunionVerdict verdict, Integer probability, String myBreakupType,
                              String partnerType, String reason, LocalDateTime createdAt) {
        this.verdict = verdict;
        this.probability = probability;
        this.myBreakupType = myBreakupType;
        this.partnerType = partnerType;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public static AssessmentResponse from(Assessment assessment) {
        return new AssessmentResponse(
                assessment.getVerdict(),
                assessment.getProbability(),
                assessment.getMyBreakupType().getLabel(),
                assessment.getPartnerType().getLabel(),
                assessment.getReason(),
                assessment.getCreatedAt());
    }
}
