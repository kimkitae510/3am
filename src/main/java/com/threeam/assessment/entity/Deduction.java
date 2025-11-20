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
    private String evidence; // 대화 속 근거

    private Deduction(String signal, int delta, String evidence) {
        this.signal = signal;
        this.delta = delta;
        this.evidence = evidence;
    }

    // points = LLM이 "이만큼 깎으세요"로 준 양수. 저장은 음수 delta로 통일한다.
    public static Deduction of(String signal, int points, String evidence) {
        return new Deduction(signal, -Math.abs(points), evidence);
    }

    // 가점 항목. LLM이 부호를 어떻게 주든 양수 delta로 통일한다.
    public static Deduction boostOf(String signal, int points, String evidence) {
        return new Deduction(signal, Math.abs(points), evidence);
    }
}
