package com.threeam.usage;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InMemoryRateLimiterTest {

    private final InMemoryRateLimiter limiter = new InMemoryRateLimiter();

    @Test
    @DisplayName("한도 안이면 통과, 넘으면 RATE_LIMITED")
    void check_exceedsLimit() {
        limiter.check("login", "ip:1.2.3.4", 2, 60);
        limiter.check("login", "ip:1.2.3.4", 2, 60);

        assertThatThrownBy(() -> limiter.check("login", "ip:1.2.3.4", 2, 60))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RATE_LIMITED);
    }

    @Test
    @DisplayName("창(window)이 지나면 카운터가 새로 시작된다")
    void check_windowResets() {
        // windowSeconds=0 → 매 호출이 새 창
        limiter.check("login", "ip:1.2.3.4", 1, 0);

        assertThatCode(() -> limiter.check("login", "ip:1.2.3.4", 1, 0))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("주체(유저/IP)가 다르면 서로의 한도에 영향이 없다")
    void check_independentSubjects() {
        limiter.check("login", "ip:1.2.3.4", 1, 60);

        assertThatCode(() -> limiter.check("login", "ip:5.6.7.8", 1, 60))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("규칙이 다르면 같은 주체라도 카운터가 분리된다")
    void check_independentRules() {
        limiter.check("login", "ip:1.2.3.4", 1, 60);

        assertThatCode(() -> limiter.check("signup", "ip:1.2.3.4", 1, 60))
                .doesNotThrowAnyException();
    }
}
