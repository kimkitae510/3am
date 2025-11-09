package com.threeam.usage;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

// 고정창(fixed window) 방식: 창이 시작된 뒤 windowSeconds 동안의 호출 수를 센다.
// 창 경계에서 순간적으로 2배까지 통과할 수 있는 알려진 한계가 있지만,
// 어뷰징 차단 목적에는 충분하고 구현이 단순해 임시 구현으로 택했다.
@Component
public class InMemoryRateLimiter implements RateLimiter {

    // 만료된 창 정리를 시작하는 맵 크기. 유저·IP 수 대비 넉넉한 값.
    private static final int CLEANUP_THRESHOLD = 10_000;

    private record Window(long startMillis, AtomicInteger count) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    public void check(String rule, String subject, int limit, int windowSeconds) {
        long now = System.currentTimeMillis();
        long windowMillis = windowSeconds * 1000L;

        Window window = windows.compute(rule + ":" + subject, (key, current) ->
                (current == null || now - current.startMillis() >= windowMillis)
                        ? new Window(now, new AtomicInteger())
                        : current);

        // 선차감 방식 — 검사·차감 사이 레이스로 한도를 뚫는 걸 막는다.
        if (window.count().incrementAndGet() > limit) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }

        cleanUpIfLarge(now, windowMillis);
    }

    // 지난 창의 키가 무한히 쌓이지 않게, 맵이 커지면 만료분을 걷어낸다.
    private void cleanUpIfLarge(long now, long windowMillis) {
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().startMillis() >= windowMillis);
        }
    }
}
