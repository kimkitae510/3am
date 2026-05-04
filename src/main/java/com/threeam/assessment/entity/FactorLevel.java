package com.threeam.assessment.entity;

// 요인 판정 3단계. LLM은 판정만 내고 숫자는 TypeBandScorer가 요인별 상수로 환산한다.
public enum FactorLevel {
    FAVORABLE("유리"),
    NEUTRAL("중립"),
    UNFAVORABLE("불리");

    private final String label;

    FactorLevel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
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
