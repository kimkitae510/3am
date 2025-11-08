package com.threeam.usage;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 임시 인메모리 구현. 단일 인스턴스 전제이고, 재시작하면 카운터가 리셋되는 한계가 있다.
// 운영 전환 시 Redis(INCR+EXPIRE를 Lua로 원자화) 구현으로 교체한다.
@Component
@RequiredArgsConstructor
public class InMemoryUsageLimiter implements UsageLimiter {

    // 일일 쿼터의 하루 경계. DB 타임존과 동일하게 맞춘다.
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final UsageProperties properties;

    // 사연별 "생성 진행 중" 표시. 값은 만료 시각(epoch ms) — 지난 값은 풀린 것으로 취급한다.
    private final ConcurrentHashMap<String, Long> inFlight = new ConcurrentHashMap<>();

    // 유저·종류별 오늘 사용량. 날짜가 바뀌면 통째로 비운다.
    private final ConcurrentHashMap<String, AtomicInteger> daily = new ConcurrentHashMap<>();
    private volatile LocalDate window = LocalDate.now(KST);

    @Override
    public void acquireInFlight(UsageKind kind, Long storyId) {
        long now = System.currentTimeMillis();
        long expireAt = now + properties.getInFlightTtlSeconds() * 1000;
        // compute가 원자적이라, 동시에 들어와도 한 요청만 acquired가 된다.
        boolean[] acquired = {false};
        inFlight.compute(key(kind, storyId), (key, expiry) -> {
            if (expiry == null || expiry <= now) {
                acquired[0] = true;
                return expireAt;
            }
            return expiry;
        });
        if (!acquired[0]) {
            throw new BusinessException(ErrorCode.GENERATION_IN_PROGRESS);
        }
    }

    @Override
    public void releaseInFlight(UsageKind kind, Long storyId) {
        inFlight.remove(key(kind, storyId));
    }

    @Override
    public void consumeDaily(UsageKind kind, Long userId) {
        rolloverIfNeeded();
        AtomicInteger used = daily.computeIfAbsent(key(kind, userId), key -> new AtomicInteger());
        // 선차감 후 초과분을 되돌린다 — 검사·차감 사이의 레이스로 한도를 뚫는 걸 막는다.
        if (used.incrementAndGet() > limitOf(kind)) {
            used.decrementAndGet();
            throw new BusinessException(ErrorCode.QUOTA_EXCEEDED);
        }
    }

    @Override
    public void refundDaily(UsageKind kind, Long userId) {
        AtomicInteger used = daily.get(key(kind, userId));
        if (used != null) {
            used.updateAndGet(value -> Math.max(0, value - 1));
        }
    }

    private int limitOf(UsageKind kind) {
        return kind == UsageKind.CHAT
                ? properties.getChatDailyLimit()
                : properties.getAssessmentDailyLimit();
    }

    private String key(UsageKind kind, Long id) {
        return kind.name() + ":" + id;
    }

    // 스케줄러 없이, 요청이 들어온 시점에 날짜가 바뀌었으면 비우는 lazy 방식.
    private void rolloverIfNeeded() {
        LocalDate today = LocalDate.now(KST);
        if (!today.equals(window)) {
            synchronized (this) {
                if (!today.equals(window)) {
                    daily.clear();
                    window = today;
                }
            }
        }
    }
}
