package com.threeam.usage;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InMemoryUsageLimiterTest {

    private UsageProperties properties;
    private InMemoryUsageLimiter limiter;

    @BeforeEach
    void setUp() {
        properties = new UsageProperties();
        properties.setChatDailyLimit(2);
        properties.setAssessmentDailyLimit(1);
        properties.setInFlightTtlSeconds(120);
        limiter = new InMemoryUsageLimiter(properties);
    }

    @Test
    @DisplayName("in-flight - 생성 중인 사연에 다시 접수하면 GENERATION_IN_PROGRESS")
    void acquireInFlight_rejectsDuplicate() {
        limiter.acquireInFlight(UsageKind.CHAT, 10L);

        assertThatThrownBy(() -> limiter.acquireInFlight(UsageKind.CHAT, 10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GENERATION_IN_PROGRESS);
    }

    @Test
    @DisplayName("in-flight - 해제하면 같은 사연에 다시 접수할 수 있다")
    void acquireInFlight_afterRelease() {
        limiter.acquireInFlight(UsageKind.CHAT, 10L);
        limiter.releaseInFlight(UsageKind.CHAT, 10L);

        assertThatCode(() -> limiter.acquireInFlight(UsageKind.CHAT, 10L))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("in-flight - TTL이 지난 잠금은 풀린 것으로 보고 접수한다(해제 유실 대비)")
    void acquireInFlight_expiredLock() {
        properties.setInFlightTtlSeconds(0);
        limiter.acquireInFlight(UsageKind.CHAT, 10L);

        assertThatCode(() -> limiter.acquireInFlight(UsageKind.CHAT, 10L))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("in-flight - 사연이 다르면 서로 막지 않는다")
    void acquireInFlight_independentStories() {
        limiter.acquireInFlight(UsageKind.CHAT, 10L);

        assertThatCode(() -> limiter.acquireInFlight(UsageKind.CHAT, 11L))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("in-flight - 같은 사연이라도 대화와 진단 잠금은 별개다")
    void acquireInFlight_independentKinds() {
        limiter.acquireInFlight(UsageKind.CHAT, 10L);

        assertThatCode(() -> limiter.acquireInFlight(UsageKind.ASSESSMENT, 10L))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("일일 쿼터 - 한도를 넘으면 QUOTA_EXCEEDED")
    void consumeDaily_exceedsLimit() {
        limiter.consumeDaily(UsageKind.CHAT, 1L);
        limiter.consumeDaily(UsageKind.CHAT, 1L);

        assertThatThrownBy(() -> limiter.consumeDaily(UsageKind.CHAT, 1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.QUOTA_EXCEEDED);
    }

    @Test
    @DisplayName("일일 쿼터 - 유저가 다르면 서로의 한도에 영향이 없다")
    void consumeDaily_independentUsers() {
        limiter.consumeDaily(UsageKind.CHAT, 1L);
        limiter.consumeDaily(UsageKind.CHAT, 1L);

        assertThatCode(() -> limiter.consumeDaily(UsageKind.CHAT, 2L))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("일일 쿼터 - 대화 한도를 다 써도 진단 쿼터는 별도로 남는다")
    void consumeDaily_independentKinds() {
        limiter.consumeDaily(UsageKind.CHAT, 1L);
        limiter.consumeDaily(UsageKind.CHAT, 1L);

        assertThatCode(() -> limiter.consumeDaily(UsageKind.ASSESSMENT, 1L))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("환급 - 되돌리면 그만큼 다시 쓸 수 있다")
    void refundDaily_restoresQuota() {
        limiter.consumeDaily(UsageKind.CHAT, 1L);
        limiter.consumeDaily(UsageKind.CHAT, 1L);
        limiter.refundDaily(UsageKind.CHAT, 1L);

        assertThatCode(() -> limiter.consumeDaily(UsageKind.CHAT, 1L))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("환급 - 사용량이 0 밑으로 내려가지 않는다(환급 반복으로 한도 부풀리기 차단)")
    void refundDaily_neverBelowZero() {
        limiter.refundDaily(UsageKind.CHAT, 1L);
        limiter.refundDaily(UsageKind.CHAT, 1L);

        limiter.consumeDaily(UsageKind.CHAT, 1L);
        limiter.consumeDaily(UsageKind.CHAT, 1L);
        assertThatThrownBy(() -> limiter.consumeDaily(UsageKind.CHAT, 1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.QUOTA_EXCEEDED);
    }
}
