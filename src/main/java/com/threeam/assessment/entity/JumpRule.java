package com.threeam.assessment.entity;

// 유형 대역을 통째로 교체하는 점프 규칙. 셋 다 "이별의 성질보다 판을 크게 바꾸는 사실"이라
// 요인 가감으로는 못 담는다. 동시에 성립하면 LLM이 가장 강한 하나만 고른다(스키마 단일 값).
public enum JumpRule {
    NONE("없음"),
    // 유저가 통보한 이별은 유형을 매기지 않는다(유형 사전은 상대가 떠난 이별의 문법) —
    // 대신 상대의 미련 단계 3단이 대역을 정한다. 뚜렷/흔적/없음의 중간이 없으면
    // "정리해보겠다" 같은 수용 직후 판이 바닥이나 최상단으로 튀는 오판이 실측됐다.
    USER_DUMPED("유저통보상대미련"),        // 미련 뚜렷 — 관계가 실제로 움직임
    USER_DUMPED_FAINT("유저통보미련흔적"),  // 수용했지만 흔적 남음 — 문이 닫히는 중
    USER_DUMPED_NONE("유저통보미련없음"),   // 수용 완료 — 반응 없음
    // 닫혀 있던 상대가 통로를 열고(차단 해제, 재공개) 먼저 안부 수준의 연락을 해온 판.
    // 잔신호(요인 ±10)와 재회의사(60~85) 사이의 공백을 메운다 — 차단 해제 + 첫 연락이
    // 요인 관통 한도(+12)에 막혀 낮은 대역을 못 벗어나던 실측 대응.
    PARTNER_RECONTACT("상대접촉재개"),
    PARTNER_HINT("상대재회의사"),      // 상대가 재회 의사를 내비침(간 보기, 만나자는 말) — 제안 직전 단계
    REPEAT_CYCLE("반복재회패턴"),      // 재회 이력 + 같은 사유의 반복 — 돌아오는 패턴이 입증된 관계
    // 하향 점프 — 위로만 휙 움직이는 체계라, 유리한 유형(충동형 하한)에 갇힌 닫힌 판이
    // 과대평가되던 구멍을 메운다. 죽은 판은 죽었다고 말해야 확률이 사실을 잰다.
    PARTNER_CLOSED("상대문닫힘"),      // 명시적 정리 요구 + 차단/무응답 유지 + 재접촉 거부
    PARTNER_MARRIED("상대결혼약혼");   // 결혼/약혼 확인 — 새 연인 정착보다 위의 종결 신호

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
