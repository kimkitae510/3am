package com.threeam.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.threeam.global.exception.custom.BusinessException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 검사·차감(check-then-act)이 원자적이지 않으면 동시 요청이 한도·잠금을 뚫는다.
// 그 레이스가 실제로 막히는지를 스레드를 띄워 검증한다.
class InMemoryUsageLimiterConcurrencyTest {

    private UsageProperties properties;
    private InMemoryUsageLimiter limiter;

    @BeforeEach
    void setUp() {
        properties = new UsageProperties();
        properties.setInFlightTtlSeconds(120);
        limiter = new InMemoryUsageLimiter(properties);
    }

    @Test
    @DisplayName("in-flight - 같은 사연에 동시 100 요청이 몰려도 잠금 획득은 정확히 1건")
    void acquireInFlight_exactlyOneWinsUnderContention() throws InterruptedException {
        int threads = 100;
        AtomicInteger acquired = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        runConcurrently(threads, () -> {
            try {
                limiter.acquireInFlight(UsageKind.CHAT, 10L);
                acquired.incrementAndGet();
            } catch (BusinessException e) {
                rejected.incrementAndGet();
            }
        });

        assertThat(acquired.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
    }

    @Test
    @DisplayName("일일 쿼터 - 한도 50에 동시 500 차감이 몰려도 통과는 정확히 50건")
    void consumeDaily_neverExceedsLimitUnderContention() throws InterruptedException {
        properties.setChatDailyLimit(50);
        int threads = 500;
        AtomicInteger consumed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        runConcurrently(threads, () -> {
            try {
                limiter.consumeDaily(UsageKind.CHAT, 1L);
                consumed.incrementAndGet();
            } catch (BusinessException e) {
                rejected.incrementAndGet();
            }
        });

        assertThat(consumed.get()).isEqualTo(50);
        assertThat(rejected.get()).isEqualTo(threads - 50);
    }

    // 모든 스레드를 latch 앞에 세워뒀다가 한 번에 출발시켜 경합을 최대로 만든다.
    private void runConcurrently(int threads, Runnable task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
    }
}
