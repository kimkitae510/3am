package com.threeam.assessment.entity;

// 유지 전망 — 재회가 '성사될' 확률과 별개의 축. 같은 이유로 다시 헤어질 위험을 따로 말해준다
// (환승, 반복이별은 성사는 쉬운데 유지가 안 되는 대표 유형이라, 확률 하나로 뭉치면 유저를 속인다).
public enum RelapseRisk {
    LOW("낮음"),
    MEDIUM("중간"),
    HIGH("높음");

    private final String label;

    RelapseRisk(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static RelapseRisk fromLabel(String label) {
        if (label == null) {
            return null;
        }
        for (RelapseRisk risk : values()) {
            if (risk.label.equals(label.trim())) {
                return risk;
            }
        }
        return null;
    }
}
