package com.threeam.usage;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 일일 쿼터와 생성 락 모두 DB에 둔다. 재시작, 멀티인스턴스에서도 유지되고, 인메모리 잠금이
// 인스턴스마다 따로 세던 문제(한도 우회)를 없앤다. 생성 락은 유저+종류 단위 분산 락으로,
// 같은 유저의 동시 생성을 하나로 직렬화해 후차감 한도 초과(TOCTOU)까지 함께 막는다.
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
    private final GenerationLockRepository generationLockRepository;

    @Override
    @Transactional
    public void acquireInFlight(UsageKind kind, Long userId) {
        LocalDateTime now = LocalDateTime.now(KST);
        LocalDateTime until = now.plusSeconds(properties.lockTtlSeconds(kind));
        // 원자 upsert. 반환 0이면 아직 유효한 락이 있다는 뜻 — 이미 생성 중이므로 거부한다.
        if (generationLockRepository.acquire(lockKey(kind, userId), now, until) == 0) {
            throw new BusinessException(ErrorCode.GENERATION_IN_PROGRESS);
        }
    }

    @Override
    @Transactional
    public void releaseInFlight(UsageKind kind, Long userId) {
        generationLockRepository.release(lockKey(kind, userId));
    }

    private String lockKey(UsageKind kind, Long userId) {
        return kind.name() + ":" + userId;
    }

    @Override
    @Transactional(readOnly = true)
    public void checkDaily(UsageKind kind, Long userId) {
        if (freeUsedToday(kind, userId) < dailyLimit(kind, userId)) {
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
        if (freeUsedToday(kind, userId) < dailyLimit(kind, userId)) {
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
        return Math.max(0, dailyLimit(kind, userId) - freeUsedToday(kind, userId));
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

    // 한도는 종류별 고정 — 가입 첫날 상향은 폐지(가입 선물 이용권으로 대체).
    @Override
    public int dailyLimit(UsageKind kind, Long userId) {
        return limitOf(kind);
    }

    private int limitOf(UsageKind kind) {
        return kind == UsageKind.CHAT
                ? properties.getChatDailyLimit()
                : properties.getAssessmentDailyLimit();
    }
}
