package com.threeam.assessment.entity;

// 유형 대역을 통째로 교체하는 점프 규칙. 셋 다 "이별의 성질보다 판을 크게 바꾸는 사실"이라
// 요인 가감으로는 못 담는다. 동시에 성립하면 LLM이 가장 강한 하나만 고른다(스키마 단일 값).
public enum JumpRule {
    NONE("없음"),
    USER_DUMPED("유저통보상대미련"),   // 유저가 통보했고 상대에게 미련 — 받아줄 확률의 문제
    PARTNER_HINT("상대재회의사"),      // 상대가 재회 의사를 내비침(간 보기, 만나자는 말) — 제안 직전 단계
    REPEAT_CYCLE("반복재회패턴");      // 재회 이력 + 같은 사유의 반복 — 돌아오는 패턴이 입증된 관계

    private final String label;

    JumpRule(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static JumpRule fromLabel(String label) {
        if (label == null) {
            return JumpRule.NONE;
        }
        for (JumpRule rule : values()) {
            if (rule.label.equals(label.trim())) {
                return rule;
            }
        }
        return JumpRule.NONE;
    }
}
