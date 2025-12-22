package com.threeam.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.threeam.global.exception.custom.BusinessException;
import com.threeam.global.exception.ErrorCode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DbUsageLimiterTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Mock
    private UsageQuotaRepository quotaRepository;

    @Mock
    private EntitlementRepository entitlementRepository;

    private UsageProperties properties;
    private DbUsageLimiter limiter;

    @BeforeEach
    void setUp() {
        properties = new UsageProperties();
        properties.setChatDailyLimit(30);
        properties.setAssessmentDailyLimit(3);
        properties.setInFlightTtlSeconds(120);
        limiter = new DbUsageLimiter(properties, quotaRepository, entitlementRepository);
    }

    private UsageQuota quota(LocalDate date, int used) {
        UsageQuota quota = newQuota();
        ReflectionTestUtils.setField(quota, "quotaDate", date);
        ReflectionTestUtils.setField(quota, "usedCount", used);
        return quota;
    }

    private UsageQuota newQuota() {
        try {
            var ctor = UsageQuota.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("한도 검사 - 무료 한도 소진 + 이용권 없음이면 QUOTA_EXCEEDED")
    void checkDaily_exceeded() {
        given(quotaRepository.findByUserIdAndKind(1L, UsageKind.ASSESSMENT))
                .willReturn(Optional.of(quota(TODAY, 3)));
        given(entitlementRepository.remainingOf(1L, UsageKind.ASSESSMENT)).willReturn(0L);

        assertThatThrownBy(() -> limiter.checkDaily(UsageKind.ASSESSMENT, 1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.QUOTA_EXCEEDED);
    }

    @Test
    @DisplayName("한도 검사 - 무료 한도를 다 썼어도 이용권이 남아 있으면 통과한다")
    void checkDaily_paidEntitlementPasses() {
        given(quotaRepository.findByUserIdAndKind(1L, UsageKind.ASSESSMENT))
                .willReturn(Optional.of(quota(TODAY, 3)));
        given(entitlementRepository.remainingOf(1L, UsageKind.ASSESSMENT)).willReturn(2L);

        assertThatCode(() -> limiter.checkDaily(UsageKind.ASSESSMENT, 1L))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("한도 검사 - 행의 날짜가 지났으면(자정 경과) 한도가 차 있어도 통과한다(리셋 대상)")
    void checkDaily_staleRowPasses() {
        given(quotaRepository.findByUserIdAndKind(1L, UsageKind.ASSESSMENT))
                .willReturn(Optional.of(quota(TODAY.minusDays(1), 3)));

        assertThatCode(() -> limiter.checkDaily(UsageKind.ASSESSMENT, 1L))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("한도 검사 - 오늘 처음이면(행 없음) 통과한다")
    void checkDaily_firstUsePasses() {
        given(quotaRepository.findByUserIdAndKind(1L, UsageKind.CHAT))
                .willReturn(Optional.empty());

        assertThatCode(() -> limiter.checkDaily(UsageKind.CHAT, 1L))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("사용 기록 - 무료 한도가 남아 있으면 오늘 날짜(KST)로 원자 upsert를 호출한다")
    void recordDaily_delegatesAtomicUpsert() {
        given(quotaRepository.findByUserIdAndKind(1L, UsageKind.CHAT))
                .willReturn(Optional.empty());

        limiter.recordDaily(UsageKind.CHAT, 1L);

        verify(quotaRepository).recordUsage(eq(1L), eq("CHAT"), eq(TODAY));
    }

    @Test
    @DisplayName("사용 기록 - 무료 한도 소진 시 이용권에서 차감하고 무료 카운터는 건드리지 않는다")
    void recordDaily_consumesEntitlementAfterFreeLimit() {
        given(quotaRepository.findByUserIdAndKind(1L, UsageKind.ASSESSMENT))
                .willReturn(Optional.of(quota(TODAY, 3)));
        given(entitlementRepository.findConsumableIds(1L, UsageKind.ASSESSMENT))
                .willReturn(List.of(10L));
        given(entitlementRepository.consumeOne(10L)).willReturn(1);

        limiter.recordDaily(UsageKind.ASSESSMENT, 1L);

        verify(entitlementRepository).consumeOne(10L);
        verify(quotaRepository, never()).recordUsage(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("사용 기록 - 첫 이용권 차감이 경합에 지면 다음 후보에서 차감한다")
    void recordDaily_fallsThroughToNextEntitlement() {
        given(quotaRepository.findByUserIdAndKind(1L, UsageKind.ASSESSMENT))
                .willReturn(Optional.of(quota(TODAY, 3)));
        given(entitlementRepository.findConsumableIds(1L, UsageKind.ASSESSMENT))
                .willReturn(List.of(10L, 11L));
        given(entitlementRepository.consumeOne(10L)).willReturn(0);   // 그 사이 소진/환불
        given(entitlementRepository.consumeOne(11L)).willReturn(1);

        limiter.recordDaily(UsageKind.ASSESSMENT, 1L);

        verify(entitlementRepository).consumeOne(11L);
        verify(quotaRepository, never()).recordUsage(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("사용 기록 - 이용권 차감이 전부 실패하면 무료 카운터에 초과 기록으로 남긴다")
    void recordDaily_fallsBackToFreeCounter() {
        given(quotaRepository.findByUserIdAndKind(1L, UsageKind.ASSESSMENT))
                .willReturn(Optional.of(quota(TODAY, 3)));
        given(entitlementRepository.findConsumableIds(1L, UsageKind.ASSESSMENT))
                .willReturn(List.of());

        limiter.recordDaily(UsageKind.ASSESSMENT, 1L);

        verify(quotaRepository).recordUsage(eq(1L), eq("ASSESSMENT"), eq(TODAY));
    }

    @Test
    @DisplayName("이용권 잔여 조회 - 리포지토리 합계를 그대로 돌려준다")
    void paidRemaining_delegates() {
        given(entitlementRepository.remainingOf(1L, UsageKind.ASSESSMENT)).willReturn(7L);

        assertThat(limiter.paidRemaining(UsageKind.ASSESSMENT, 1L)).isEqualTo(7);
    }

    @Test
    @DisplayName("잔여 조회 - 오늘 쓴 만큼 뺀 값을, 행이 없거나 지난 날짜면 전체 한도를 돌려준다")
    void remainingDaily_countsOnlyToday() {
        given(quotaRepository.findByUserIdAndKind(1L, UsageKind.ASSESSMENT))
                .willReturn(Optional.of(quota(TODAY, 2)));
        assertThat(limiter.remainingDaily(UsageKind.ASSESSMENT, 1L)).isEqualTo(1);   // 3 - 2

        given(quotaRepository.findByUserIdAndKind(2L, UsageKind.ASSESSMENT))
                .willReturn(Optional.of(quota(TODAY.minusDays(1), 3)));
        assertThat(limiter.remainingDaily(UsageKind.ASSESSMENT, 2L)).isEqualTo(3);   // 지난 날짜 = 리셋 대상

        given(quotaRepository.findByUserIdAndKind(3L, UsageKind.CHAT))
                .willReturn(Optional.empty());
        assertThat(limiter.remainingDaily(UsageKind.CHAT, 3L)).isEqualTo(30);        // 첫 사용 전

        given(quotaRepository.findByUserIdAndKind(4L, UsageKind.ASSESSMENT))
                .willReturn(Optional.of(quota(TODAY, 5)));
        assertThat(limiter.remainingDaily(UsageKind.ASSESSMENT, 4L)).isZero();       // 초과 기록도 음수 없이 0
    }

    @Test
    @DisplayName("in-flight - 동시 100 요청이 같은 사연 잠금을 다퉈도 1건만 획득한다")
    void acquireInFlight_concurrent() throws InterruptedException {
        int threads = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger acquired = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    limiter.acquireInFlight(UsageKind.CHAT, 1L, 10L);
                    acquired.incrementAndGet();
                } catch (Exception ignored) {
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(acquired.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("in-flight - 해제하면 다시 획득할 수 있고, TTL이 지난 잠금은 무시된다")
    void acquireInFlight_releaseAndTtl() {
        limiter.acquireInFlight(UsageKind.CHAT, 1L, 10L);
        limiter.releaseInFlight(UsageKind.CHAT, 1L, 10L);
        assertThatCode(() -> limiter.acquireInFlight(UsageKind.CHAT, 1L, 10L))
                .doesNotThrowAnyException();

        // TTL 0으로 만들면 방금 잡은 잠금도 만료로 취급 → 재획득 가능(서버 죽음 대비 자동 해제)
        properties.setInFlightTtlSeconds(0);
        limiter.acquireInFlight(UsageKind.CHAT, 2L, 20L);
        assertThatCode(() -> limiter.acquireInFlight(UsageKind.CHAT, 2L, 20L))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("in-flight - 한 유저가 서로 다른 사연으로 동시 생성해도 상한(3)을 넘으면 거부한다")
    void acquireInFlight_perUserConcurrencyCap() {
        // 사연이 달라 사연 잠금은 각각 통과하지만, 유저 동시 상한이 4번째를 막는다.
        limiter.acquireInFlight(UsageKind.CHAT, 7L, 101L);
        limiter.acquireInFlight(UsageKind.CHAT, 7L, 102L);
        limiter.acquireInFlight(UsageKind.CHAT, 7L, 103L);

        assertThatThrownBy(() -> limiter.acquireInFlight(UsageKind.CHAT, 7L, 104L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GENERATION_IN_PROGRESS);

        // 하나 해제하면 다시 한 자리가 열린다.
        limiter.releaseInFlight(UsageKind.CHAT, 7L, 101L);
        assertThatCode(() -> limiter.acquireInFlight(UsageKind.CHAT, 7L, 104L))
                .doesNotThrowAnyException();
    }
}
