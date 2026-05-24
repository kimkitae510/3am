package com.threeam.user.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SignupRateLimiterTest {

    private static final int MAX_PER_DAY = 5;

    @Test
    @DisplayName("같은 IP는 하루 상한까지 통과하고 그 다음부터 막힌다")
    void limitPerIp() {
        SignupRateLimiter limiter = new SignupRateLimiter();

        for (int i = 0; i < MAX_PER_DAY; i++) {
            int attempt = i;
            assertThatCode(() -> limiter.check("1.1.1.1"))
                    .as("%d번째 시도는 통과해야 한다", attempt + 1)
                    .doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> limiter.check("1.1.1.1"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SIGNUP_RATE_LIMITED);
    }

    @Test
    @DisplayName("다른 IP는 서로의 카운터에 영향을 주지 않는다")
    void countersAreIndependent() {
        SignupRateLimiter limiter = new SignupRateLimiter();
        for (int i = 0; i < MAX_PER_DAY; i++) {
            limiter.check("1.1.1.1");
        }

        assertThatCode(() -> limiter.check("2.2.2.2")).doesNotThrowAnyException();
    }

    // 둘러보기는 같은 카운터를 쓰되 문구가 달라야 한다 — 가입한 적 없는 사람에게 가입 문구가 나가면 안 된다.
    @Test
    @DisplayName("호출한 쪽이 지정한 에러 코드로 거절한다")
    void rejectsWithCallerErrorCode() {
        SignupRateLimiter limiter = new SignupRateLimiter();
        for (int i = 0; i < MAX_PER_DAY; i++) {
            limiter.check("3.3.3.3", ErrorCode.GUEST_START_LIMITED);
        }

        assertThatThrownBy(() -> limiter.check("3.3.3.3", ErrorCode.GUEST_START_LIMITED))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GUEST_START_LIMITED);
    }

    // 지난 날짜 항목이 안 지워지면 방문자 수만큼 맵이 무한히 커진다(누수).
    @Test
    @DisplayName("정리는 오늘 것을 남기고 지난 날짜 항목만 지운다")
    void evictKeepsToday() {
        SignupRateLimiter limiter = new SignupRateLimiter();
        for (int i = 0; i < MAX_PER_DAY; i++) {
            limiter.check("4.4.4.4");
        }

        limiter.evictStale();

        // 오늘 카운터가 살아 있어야 정리가 상한을 무력화하지 않는다.
        assertThatThrownBy(() -> limiter.check("4.4.4.4"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SIGNUP_RATE_LIMITED);
    }
}
