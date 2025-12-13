package com.threeam.assessment.dto;

import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.AttachmentStyle;
import com.threeam.assessment.entity.ReunionVerdict;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;

@Getter
public class AssessmentResponse {

    private final ReunionVerdict verdict;
    private final Integer probability;      // POSSIBLE이 아니면 null. 상대 제안 유효 시 100
    private final String myAttachment;      // 유저 애착유형 라벨(한국어), 근거 부족이면 null
    private final String partnerAttachment; // 상대 애착유형 라벨(한국어), 근거 부족이면 null
    private final String reason;
    private final List<DeductionView> deductions;
    private final LocalDateTime createdAt;

    private AssessmentResponse(ReunionVerdict verdict, Integer probability, String myAttachment,
                              String partnerAttachment, String reason,
                              List<DeductionView> deductions, LocalDateTime createdAt) {
        this.verdict = verdict;
        this.probability = probability;
        this.myAttachment = myAttachment;
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
                label(assessment.getMyAttachment()),
                label(assessment.getPartnerAttachment()),
                assessment.getReason(),
                deductions,
                assessment.getCreatedAt());
    }

    private static String label(AttachmentStyle style) {
        return style == null ? null : style.getLabel();
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
