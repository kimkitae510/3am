package com.threeam.assessment.entity;

// 대체자 요인의 불리 세분. 썸 정황과 새 연인 정착은 확률을 누르는 힘이 다르다
// (정착만 유형 대역의 하한을 뚫고 내려간다 — 시간보다 대체자가 실질 마감선이라는 조사 결론).
public enum ReplacementStage {
    SEEING("정황"),
    SETTLED("정착");

    private final String label;

    ReplacementStage(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static ReplacementStage fromLabel(String label) {
        if (label == null) {
            return null;
        }
        for (ReplacementStage stage : values()) {
            if (stage.label.equals(label.trim())) {
                return stage;
            }
        }
        return null;
    }
}
