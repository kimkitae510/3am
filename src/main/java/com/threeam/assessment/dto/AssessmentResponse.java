package com.threeam.assessment.dto;

import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.AttachmentConfidence;
import com.threeam.assessment.entity.AttachmentStyle;
import com.threeam.assessment.entity.GuidanceKind;
import com.threeam.assessment.entity.ReunionVerdict;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;

@Getter
public class AssessmentResponse {

    private final ReunionVerdict verdict;
    private final Integer probability;      // POSSIBLE이 아니면 null. 상대 제안 유효 시 100
    private final String partnerAttachment; // 상대 애착유형 라벨(한국어), 근거 부족이면 null
    private final String attachmentConfidence; // CONFIRMED | TENTATIVE, 유형이 null이면 null
    private final List<AttachmentSignalView> attachmentSignals; // 유형 판정 근거 목록
    private final String reason;
    private final List<DeductionView> deductions;
    private final List<GuidanceView> guidance; // 행동 가이드(do/dont). POSSIBLE 외에는 빈 목록
    private final LocalDateTime createdAt;

    private AssessmentResponse(ReunionVerdict verdict, Integer probability,
                              String partnerAttachment, String attachmentConfidence,
                              List<AttachmentSignalView> attachmentSignals,
                              String reason, List<DeductionView> deductions,
                              List<GuidanceView> guidance, LocalDateTime createdAt) {
        this.verdict = verdict;
        this.probability = probability;
        this.partnerAttachment = partnerAttachment;
        this.attachmentConfidence = attachmentConfidence;
        this.attachmentSignals = attachmentSignals;
        this.reason = reason;
        this.deductions = deductions;
        this.guidance = guidance;
        this.createdAt = createdAt;
    }

    public static AssessmentResponse from(Assessment assessment) {
        List<DeductionView> deductions = assessment.getDeductions().stream()
                .map(d -> new DeductionView(d.getSignal(), d.getDelta(), d.getEvidence()))
                .toList();
        List<AttachmentSignalView> attachmentSignals = assessment.getAttachmentSignals().stream()
                .map(s -> new AttachmentSignalView(s.getSignal(), s.getEvidence()))
                .toList();
        List<GuidanceView> guidance = assessment.getGuidanceItems().stream()
                .map(g -> new GuidanceView(g.getKind(), g.getAdvice(), g.getBasis()))
                .toList();
        AttachmentConfidence confidence = assessment.getAttachmentConfidence();
        return new AssessmentResponse(
                assessment.getVerdict(),
                assessment.getProbability(),
                label(assessment.getPartnerAttachment()),
                confidence == null ? null : confidence.name(),
                attachmentSignals,
                assessment.getReason(),
                deductions,
                guidance,
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

    @Getter
    public static class AttachmentSignalView {
        private final String signal;
        private final String evidence;

        private AttachmentSignalView(String signal, String evidence) {
            this.signal = signal;
            this.evidence = evidence;
        }
    }

    @Getter
    public static class GuidanceView {
        private final GuidanceKind kind;
        private final String advice;
        private final String basis;

        private GuidanceView(GuidanceKind kind, String advice, String basis) {
            this.kind = kind;
            this.advice = advice;
            this.basis = basis;
        }
    }
}
