package com.threeam.assessment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 애착유형 판정의 근거가 된 행동 패턴 하나. 감점(Deduction)과 같은 문법으로
// "왜 이 유형?"에 조목조목 답하기 위해 신호명과 관찰 근거를 목록으로 남긴다.
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttachmentSignal {

    // signal은 MySQL 예약어라 컬럼명을 signal_name으로 매핑한다(Deduction과 동일).
    @Column(name = "signal_name", nullable = false, length = 100)
    private String signal;   // "갈등 시 대화 회피"

    @Column(nullable = false, columnDefinition = "TEXT")
    private String evidence; // 대화/기록에서 관찰된 행동

    private AttachmentSignal(String signal, String evidence) {
        this.signal = signal;
        this.evidence = evidence;
    }

    public static AttachmentSignal of(String signal, String evidence) {
        return new AttachmentSignal(signal, evidence);
    }
}
