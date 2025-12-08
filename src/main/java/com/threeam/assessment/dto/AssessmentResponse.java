package com.threeam.assessment.dto;

import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.BreakupType;
import com.threeam.assessment.entity.PartnerAttachment;
import com.threeam.assessment.entity.PartnerType;
import com.threeam.assessment.entity.ReunionVerdict;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;

@Getter
public class AssessmentResponse {

    private final ReunionVerdict verdict;
    private final Integer probability;   // POSSIBLE이 아니면 null
    private final String myBreakupType;  // 라벨(한국어), 없으면 null
    private final String partnerType;    // 라벨(한국어), 없으면 null
    private final String partnerAttachment; // 애착유형 라벨(한국어), 근거 부족이면 null
    private final String reason;
    private final List<DeductionView> deductions;
    private final LocalDateTime createdAt;

    private AssessmentResponse(ReunionVerdict verdict, Integer probability, String myBreakupType,
                              String partnerType, String partnerAttachment, String reason,
                              List<DeductionView> deductions, LocalDateTime createdAt) {
        this.verdict = verdict;
        this.probability = probability;
        this.myBreakupType = myBreakupType;
        this.partnerType = partnerType;
        this.partnerAttachment = partnerAttachment;
        this.reason = reason;
        this.deductions = deductions;
        this.createdAt = createdAt;
    }

    public static AssessmentResponse from(Assessment assessment) {
        List<DeductionView> deductions = assessment.getDeductions().stream()
                .map(d -> new DeductionView(d.getSignal(), d.getDelta(), d.getEvidence()))
                .toList();
        return new AssessmentResponse(
                assessment.getVerdict(),
                assessment.getProbability(),
                label(assessment.getMyBreakupType()),
                label(assessment.getPartnerType()),
                label(assessment.getPartnerAttachment()),
                assessment.getReason(),
                deductions,
                assessment.getCreatedAt());
    }

    private static String label(BreakupType type) {
        return type == null ? null : type.getLabel();
    }

    private static String label(PartnerType type) {
        return type == null ? null : type.getLabel();
    }

    private static String label(PartnerAttachment type) {
        return type == null ? null : type.getLabel();
    }

    @Getter
    public static class DeductionView {
        private final String signal;
        private final int delta;
        private final String evidence;

        private DeductionView(String signal, int delta, String evidence) {
            this.signal = signal;
            this.delta = delta;
            this.evidence = evidence;
        }
    }
}
