package com.threeam.assessment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 관찰 포인트 — "이게 확인되면 판이 바뀐다". 행동 지시가 아니라 판독의 연장이다.
// 유저가 다음 분석을 돌릴 이유가 되는 자리라 분석과 함께 저장한다.
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WatchPoint {

    // point는 MySQL 예약어가 아니지만 효과와 짝이 보이게 watch_point로 매핑한다.
    @Column(name = "watch_point", nullable = false, length = 300)
    private String point;   // 지켜볼 사건이나 사실

    @Column(nullable = false, length = 300)
    private String effect;  // 확인되면 판이 어떻게 바뀌는지

    private WatchPoint(String point, String effect) {
        this.point = point;
        this.effect = effect;
    }

    public static WatchPoint of(String point, String effect) {
        return new WatchPoint(point, effect);
    }
}
