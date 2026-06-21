package com.threeam.match;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MatchBandTest {

    @Test
    @DisplayName("확률 대역은 프론트 밴드 경계(45, 65)와 같은 자리에서 갈린다")
    void bandBoundariesMatchGaugeLabels() {
        assertThat(MatchBand.of(44)).isEqualTo(MatchBand.LOW);
        assertThat(MatchBand.of(45)).isEqualTo(MatchBand.MID);
        assertThat(MatchBand.of(64)).isEqualTo(MatchBand.MID);
        assertThat(MatchBand.of(65)).isEqualTo(MatchBand.HIGH);
    }

    // 확률이 없으면 성공을 두 장 밀 근거도 없다.
    @Test
    @DisplayName("확률이 없는 판정은 가장 보수적인 구성으로 본다")
    void nullProbabilityFallsBackToLow() {
        assertThat(MatchBand.of(null)).isEqualTo(MatchBand.LOW);
    }

    // 유료로 산 화면이 절망만 파는 건 다른 종류의 거짓말이다.
    @Test
    @DisplayName("실패는 어느 대역에서도 한 장을 넘지 않고, 높은 대역엔 아예 안 실린다")
    void failureIsCappedAtOneAndAbsentInHighBand() {
        assertThat(MatchBand.LOW.failMax()).isEqualTo(1);
        assertThat(MatchBand.MID.failMax()).isEqualTo(1);
        assertThat(MatchBand.HIGH.failMax()).isZero();
        assertThat(MatchBand.LOW.successMax()).isEqualTo(1);
        assertThat(MatchBand.HIGH.successMax()).isEqualTo(2);
    }

    @Test
    @DisplayName("어느 대역도 세 장을 넘지 않는다")
    void neverMoreThanThreeCards() {
        for (MatchBand band : MatchBand.values()) {
            assertThat(band.totalMax()).isLessThanOrEqualTo(3);
        }
    }
}
