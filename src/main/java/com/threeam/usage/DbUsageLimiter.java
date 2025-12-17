package com.threeam.usage;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 일일 쿼터는 DB(usage_quota 단일 행), in-flight 잠금은 인메모리.
// 쿼터를 DB에 두는 이유: 재시작해도 유지되고 차감 기록이 서버 밖에 남는다.
// 잠금은 수 초짜리 휘발 상태라 인메모리로 충분하다(재시작으로 날아가도 TTL 의미와 같다).
//
// 결제 이용권(entitlements)은 무료 한도의 연장선: 검사와 차감 모두 "무료 먼저, 그다음 이용권".
// 무료를 먼저 태워야 유저에게 유리하고(이용권은 이월되는 자산), 환불 계산도 단순해진다.
@Slf4j
@Component
@RequiredArgsConstructor
public class DbUsageLimiter implements UsageLimiter {

    // 일일 쿼터의 하루 경계. DB 타임존에 묶이지 않게 날짜는 항상 여기서 계산해 넘긴다.
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final UsageProperties properties;
    private final UsageQuotaRepository quotaRepository;
    private final EntitlementRepository entitlementRepository;

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
        if (freeUsedToday(kind, userId) < limitOf(kind)) {
            return;
        }
        if (entitlementRepository.remainingOf(userId, kind) > 0) {
            return;
        }
        throw new BusinessException(ErrorCode.QUOTA_EXCEEDED);
    }

    @Override
    @Transactional
    public void recordDaily(UsageKind kind, Long userId) {
        if (freeUsedToday(kind, userId) < limitOf(kind)) {
            quotaRepository.recordUsage(userId, kind.name(), LocalDate.now(KST));
            return;
        }
        // 조건부 UPDATE가 0이면(동시 차감 경합, 그 사이 환불) 다음 이용권으로 넘어간다.
        for (Long entitlementId : entitlementRepository.findConsumableIds(userId, kind)) {
            if (entitlementRepository.consumeOne(entitlementId) == 1) {
                return;
            }
        }
        // 검사 시점엔 있던 이용권이 사라진 극단 케이스. 이미 성공한 생성을 무를 수는 없으니
        // 무료 카운터에 초과 기록으로 남긴다(한도 위 1회 허용 — 유저를 막는 것보다 낫다).
        log.warn("이용권 차감 실패, 무료 쿼터 초과 기록 userId={} kind={}", userId, kind);
        quotaRepository.recordUsage(userId, kind.name(), LocalDate.now(KST));
    }

    @Override
    @Transactional(readOnly = true)
    public int remainingDaily(UsageKind kind, Long userId) {
        return Math.max(0, limitOf(kind) - freeUsedToday(kind, userId));
    }

    @Override
    @Transactional(readOnly = true)
    public int paidRemaining(UsageKind kind, Long userId) {
        return (int) entitlementRepository.remainingOf(userId, kind);
    }

    private int freeUsedToday(UsageKind kind, Long userId) {
        LocalDate today = LocalDate.now(KST);
        return quotaRepository.findByUserIdAndKind(userId, kind)
                .filter(quota -> today.equals(quota.getQuotaDate()))   // 지난 날짜 행은 리셋 대상 = 0회
                .map(UsageQuota::getUsedCount)
                .orElse(0);
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
