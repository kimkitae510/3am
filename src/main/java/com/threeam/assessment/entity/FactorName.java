package com.threeam.assessment.entity;

import java.util.Arrays;
import java.util.List;

// 진단 조정 요인(2층)의 고정 5슬롯. 순서가 곧 무게 순서다(화면도 이 순서로 보여준다).
// 자유 신호 대신 슬롯을 고정한 이유: 같은 사실이면 같은 자리에 같은 판정이 재현되게 —
// v1의 자유 signal은 진단마다 이름과 점수가 달라져 확률이 출렁였다.
public enum FactorName {
    PARTNER_SIGNAL("상대신호"),   // 상대의 이별 후 행동 방향
    REPLACEMENT("대체자"),        // 상대에게 새 사람이 있는가
    USER_CONDUCT("유저대처"),     // 이별 후 유저의 절제 vs 매달림
    NOTICE_TONE("통보온도"),      // 통보 순간 상대의 태도
    PARTNER_PATTERN("상대패턴");  // 상대의 성향과 재회 이력

    private final String label;

    FactorName(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static List<String> labels() {
        return Arrays.stream(values()).map(FactorName::label).toList();
    }

    public static FactorName fromLabel(String label) {
        if (label == null) {
            return null;
        }
        for (FactorName name : values()) {
            if (name.label.equals(label.trim())) {
                return name;
            }
        }
        return null;
    }
}
