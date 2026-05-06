package com.threeam.assessment.entity;

// 요인 판정 5단계. LLM은 판정만 내고 숫자는 TypeBandScorer가 요인별 상수로 환산한다.
// 3단(유리/중립/불리)에서 5단으로 세분 — "만남 제안이 왔다"와 "약속만 유지 중"이
// 같은 값이 되는 해상도 문제 대응. 매우X는 요인 폭 전체, X는 절반이다.
public enum FactorLevel {
    STRONG_FAVORABLE("매우유리"),
    FAVORABLE("유리"),
    NEUTRAL("중립"),
    UNFAVORABLE("불리"),
    STRONG_UNFAVORABLE("매우불리");

    private final String label;

    FactorLevel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean favorableSide() {
        return this == STRONG_FAVORABLE || this == FAVORABLE;
    }

    public boolean unfavorableSide() {
        return this == STRONG_UNFAVORABLE || this == UNFAVORABLE;
    }

    public static FactorLevel fromLabel(String label) {
        if (label == null) {
            return null;
        }
        for (FactorLevel level : values()) {
            if (level.label.equals(label.trim())) {
                return level;
            }
        }
        return null;
    }
}
