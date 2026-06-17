package com.threeam.story.entity;

// 앞으로 마주칠 접점. 재회 가능성보다 "다시 마주칠 수밖에 없는가"를 가른다 — 무연락이라도
// 같은 직장이면 이야기가 다르다. 여러 개 고를 수 있다. NONE은 배타 선택.
public enum ContactPoint {
    NONE("없다"),
    SCHOOL_OR_WORK("같은 학교나 직장"),
    NEIGHBORHOOD("같은 동네"),
    MUTUAL_FRIENDS("공통 친구 모임"),
    SCHEDULED("잡혀 있는 일정이 있다"),
    UNSETTLED("정리할 게 남았다");

    private final String label;

    ContactPoint(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
