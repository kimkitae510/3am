package com.threeam.assessment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 진단 점수를 움직인 한 항목(감점은 음수, 가점은 양수 delta). LLM이 대화에서 짚어 내려준다.
// "왜 이 확률?"에 조목조목 답하려고 사유, 폭, 근거를 통째로 남긴다.
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Deduction {

    // signal은 MySQL 예약어라 컬럼명을 signal_name으로 매핑한다.
    @Column(name = "signal_name", nullable = false, length = 100)
    private String signal;   // "읽씹당하는 중"

    // 점수 변화량(감점 음수, 가점 양수). BASE에서 이 값들을 더해 최종 점수를 낸다.
    @Column(nullable = false)
    private int delta;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String evidence; // 대화 속 근거(관찰된 사실)

    // 왜 이 사실이 확률을 이만큼 움직이는지(판독 메커니즘). 사실만 있으면 유저가
    // "이게 왜 +15?"를 알 수 없다(실측 피드백). 과거 데이터는 null.
    @Column(length = 300)
    private String rationale;

    private Deduction(String signal, int delta, String evidence, String rationale) {
        this.signal = signal;
        this.delta = delta;
        this.evidence = evidence;
        this.rationale = rationale;
    }

    // points = LLM이 "이만큼 깎으세요"로 준 양수. 저장은 음수 delta로 통일한다.
    public static Deduction of(String signal, int points, String evidence, String rationale) {
        return new Deduction(signal, -Math.abs(points), evidence, rationale);
    }

    // 가점 항목. LLM이 부호를 어떻게 주든 양수 delta로 통일한다.
    public static Deduction boostOf(String signal, int points, String evidence, String rationale) {
        return new Deduction(signal, Math.abs(points), evidence, rationale);
    }

    // 판독 이유가 무관한 조립(점수 재합산 검증 등)용.
    public static Deduction of(String signal, int points, String evidence) {
        return of(signal, points, evidence, null);
    }

    public static Deduction boostOf(String signal, int points, String evidence) {
        return boostOf(signal, points, evidence, null);
    }
}
