package com.threeam.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WebhookRateLimiterTest {

    @Test
    @DisplayName("같은 주문의 연속 웹훅은 쿨다운 안에서 한 번만 통과한다")
    void allowsFirstThenThrottles() {
        WebhookRateLimiter limiter = new WebhookRateLimiter();

        assertThat(limiter.allow("order-1")).isTrue();   // 첫 이벤트는 통과
        assertThat(limiter.allow("order-1")).isFalse();  // 쿨다운 안 반복은 차단
        assertThat(limiter.allow("order-1")).isFalse();
    }

    @Test
    @DisplayName("주문이 다르면 서로의 쿨다운에 영향을 주지 않는다")
    void independentPerOrder() {
        WebhookRateLimiter limiter = new WebhookRateLimiter();

        assertThat(limiter.allow("order-1")).isTrue();
        assertThat(limiter.allow("order-2")).isTrue();
    }
}
