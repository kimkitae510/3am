package com.threeam.usage;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.user.entity.User;
import com.threeam.user.repository.UserRepository;
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

    // 게스트 총량 카운터 — 날짜 리셋을 타지 않도록 고정 날짜 행 하나에 계속 쌓는다.
    private static final LocalDate GUEST_TOTAL_DATE = LocalDate.of(2000, 1, 1);

    private final UsageProperties properties;
    private final UsageQuotaRepository quotaRepository;
    private final EntitlementRepository entitlementRepository;
    private final GenerationLockRepository generationLockRepository;
    private final UserRepository userRepository;

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
        boolean guest = isGuest(userId);
        // 게스트에게 진단은 0회 — 여기가 계정 연결 유도 지점이다.
        if (guest && kind == UsageKind.ASSESSMENT) {
            throw new BusinessException(ErrorCode.GUEST_LINK_REQUIRED);
        }
        if (freeUsed(kind, userId, guest) < limitOf(kind, guest)) {
            return;
        }
        if (!guest && entitlementRepository.remainingOf(userId, kind) > 0) {
            return;
        }
        // 게스트 소진은 충전(결제)이 아니라 계정 연결로 풀린다 — 코드로 안내를 가른다.
        throw new BusinessException(guest ? ErrorCode.GUEST_LINK_REQUIRED : ErrorCode.QUOTA_EXCEEDED);
    }

    @Override
    @Transactional
    public void recordDaily(UsageKind kind, Long userId) {
        boolean guest = isGuest(userId);
        if (freeUsed(kind, userId, guest) < limitOf(kind, guest)) {
            quotaRepository.recordUsage(userId, kind.name(), quotaDate(guest));
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
        quotaRepository.recordUsage(userId, kind.name(), quotaDate(guest));
    }

    @Override
    @Transactional(readOnly = true)
    public int remainingDaily(UsageKind kind, Long userId) {
        boolean guest = isGuest(userId);
        return Math.max(0, limitOf(kind, guest) - freeUsed(kind, userId, guest));
    }

    @Override
    @Transactional(readOnly = true)
    public int paidRemaining(UsageKind kind, Long userId) {
        return (int) entitlementRepository.remainingOf(userId, kind);
    }

    private int freeUsed(UsageKind kind, Long userId, boolean guest) {
        LocalDate window = quotaDate(guest);
        return quotaRepository.findByUserIdAndKind(userId, kind)
                .filter(quota -> window.equals(quota.getQuotaDate()))   // 지난 날짜 행은 리셋 대상 = 0회
                .map(UsageQuota::getUsedCount)
                .orElse(0);
    }

    // 게스트는 고정 날짜 행이라 리셋이 없다(총량). 회원은 오늘 날짜 행(일일).
    // 게스트가 회원으로 승격되면 quota_date가 과거라 자연히 0에서 다시 시작한다.
    private LocalDate quotaDate(boolean guest) {
        return guest ? GUEST_TOTAL_DATE : LocalDate.now(KST);
    }

    // 한도는 종류별 고정 — 가입 첫날 상향은 폐지(가입 선물 이용권으로 대체).
    @Override
    public int dailyLimit(UsageKind kind, Long userId) {
        return limitOf(kind, isGuest(userId));
    }

    private int limitOf(UsageKind kind, boolean guest) {
        if (guest) {
            return kind == UsageKind.CHAT ? properties.getGuestChatTotalLimit() : 0;
        }
        return kind == UsageKind.CHAT
                ? properties.getChatDailyLimit()
                : properties.getAssessmentDailyLimit();
    }

    private boolean isGuest(Long userId) {
        return userRepository.findById(userId).map(User::isGuest).orElse(false);
    }
}
