package com.threeam.usage;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 일일 쿼터는 DB(usage_quota 단일 행), in-flight 잠금은 인메모리.
// 쿼터를 DB에 두는 이유: 재시작해도 유지되고 차감 기록이 서버 밖에 남는다.
// 잠금은 수 초짜리 휘발 상태라 인메모리로 충분하다(재시작으로 날아가도 TTL 의미와 같다).
@Component
@RequiredArgsConstructor
public class DbUsageLimiter implements UsageLimiter {

    // 일일 쿼터의 하루 경계. DB 타임존에 묶이지 않게 날짜는 항상 여기서 계산해 넘긴다.
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final UsageProperties properties;
    private final UsageQuotaRepository quotaRepository;

    // 사연별 "생성 진행 중" 표시. 값은 만료 시각(epoch ms) — 지난 값은 풀린 것으로 취급한다.
    private final ConcurrentHashMap<String, Long> inFlight = new ConcurrentHashMap<>();

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
    @Transactional(readOnly = true)
    public void checkDaily(UsageKind kind, Long userId) {
        LocalDate today = LocalDate.now(KST);
        quotaRepository.findByUserIdAndKind(userId, kind)
                .filter(quota -> today.equals(quota.getQuotaDate()))   // 지난 날짜 행은 리셋 대상 = 0회로 취급
                .filter(quota -> quota.getUsedCount() >= limitOf(kind))
                .ifPresent(quota -> {
                    throw new BusinessException(ErrorCode.QUOTA_EXCEEDED);
                });
    }

    @Override
    @Transactional
    public void recordDaily(UsageKind kind, Long userId) {
        quotaRepository.recordUsage(userId, kind.name(), LocalDate.now(KST));
    }

    @Override
    @Transactional(readOnly = true)
    public int remainingDaily(UsageKind kind, Long userId) {
        LocalDate today = LocalDate.now(KST);
        int used = quotaRepository.findByUserIdAndKind(userId, kind)
                .filter(quota -> today.equals(quota.getQuotaDate()))   // 지난 날짜 행은 0회로 취급
                .map(UsageQuota::getUsedCount)
                .orElse(0);
        return Math.max(0, limitOf(kind) - used);
    }

    private int limitOf(UsageKind kind) {
        return kind == UsageKind.CHAT
                ? properties.getChatDailyLimit()
                : properties.getAssessmentDailyLimit();
    }

    private String key(UsageKind kind, Long id) {
        return kind.name() + ":" + id;
    }
}
