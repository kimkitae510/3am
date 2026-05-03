package com.threeam.assessment.dto;

import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.ReunionVerdict;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;

@Getter
public class AssessmentResponse {

    private final ReunionVerdict verdict;
    private final Integer probability;      // POSSIBLE이 아니면 null. 상대 제안 유효 시 100
    private final String reason;
    private final List<DeductionView> deductions;
    private final LocalDateTime createdAt;
    // 재시도까지 남은 초. 실패 쿨다운으로 막힌 응답에만 채워진다(그 외 null).
    // 시각이 아니라 남은 초를 주는 이유: 클라이언트 시계가 틀어져 있어도 카운트다운이 어긋나지 않는다.
    private final Integer retryAfterSeconds;

    private AssessmentResponse(ReunionVerdict verdict, Integer probability,
                              String reason, List<DeductionView> deductions,
                              LocalDateTime createdAt,
                              Integer retryAfterSeconds) {
        this.verdict = verdict;
        this.probability = probability;
        this.reason = reason;
        this.deductions = deductions;
        this.createdAt = createdAt;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public AssessmentResponse withRetryAfterSeconds(int seconds) {
        return new AssessmentResponse(verdict, probability,
                reason, deductions, createdAt, seconds);
    }

    public static AssessmentResponse from(Assessment assessment) {
        List<DeductionView> deductions = assessment.getDeductions().stream()
                .map(d -> new DeductionView(d.getSignal(), d.getDelta(), d.getEvidence(), d.getRationale()))
                .toList();
        return new AssessmentResponse(
                assessment.getVerdict(),
                assessment.getProbability(),
                assessment.getReason(),
                deductions,
                assessment.getCreatedAt(),
                null);
    }

    @Getter
    public static class DeductionView {
        private final String signal;
        private final int delta;
        private final String evidence;
        private final String rationale; // 왜 이 점수인지(판독 메커니즘). 과거 데이터는 null

        private DeductionView(String signal, int delta, String evidence, String rationale) {
            this.signal = signal;
            this.delta = delta;
            this.evidence = evidence;
            this.rationale = rationale;
        }
    }

}
