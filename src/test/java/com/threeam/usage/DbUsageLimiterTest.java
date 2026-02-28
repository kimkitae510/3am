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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
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

    @Mock
    private GenerationLockRepository generationLockRepository;

    // 게스트 판별용 조회 — 스텁 없으면 Optional.empty()라 일반 회원으로 취급된다
    @Mock
    private com.threeam.user.repository.UserRepository userRepository;

    private UsageProperties properties;
    private DbUsageLimiter limiter;

    @BeforeEach
    void setUp() {
        properties = new UsageProperties();
        properties.setChatDailyLimit(30);
        properties.setAssessmentDailyLimit(3);
        limiter = new DbUsageLimiter(properties, quotaRepository, entitlementRepository,
                generationLockRepository, userRepository);
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

    // 게스트 총량 카운터가 쌓이는 고정 날짜(DbUsageLimiter.GUEST_TOTAL_DATE와 동일해야 한다)
    private static final LocalDate GUEST_DATE = LocalDate.of(2000, 1, 1);

    private com.threeam.user.entity.User guestUser() {
        return com.threeam.user.entity.User.builder()
                .role(com.threeam.user.entity.Role.USER)
                .provider(com.threeam.user.entity.AuthProvider.GUEST)
                .providerId("guest-uuid")
                .build();
    }

    @Test
    @DisplayName("게스트 - 대화 총량(리셋 없음)을 다 쓰면 이용권 경로 없이 GUEST_LINK_REQUIRED")
    void checkDaily_guestChatTotalExhausted() {
        given(userRepository.findById(9L)).willReturn(Optional.of(guestUser()));
        // 고정 날짜 행이라 며칠이 지나도 그대로 남아 있다 — 총량 3회 소진 상태
        given(quotaRepository.findByUserIdAndKind(9L, UsageKind.CHAT))
                .willReturn(Optional.of(quota(GUEST_DATE, 3)));

        assertThatThrownBy(() -> limiter.checkDaily(UsageKind.CHAT, 9L, 1))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GUEST_LINK_REQUIRED);
        verify(entitlementRepository, never()).remainingOf(anyLong(), any()); // 게스트에게 이용권 경로는 없다
    }

    @Test
    @DisplayName("게스트 - 진단은 횟수와 무관하게 GUEST_LINK_REQUIRED(계정 연결 유도 지점)")
    void checkDaily_guestAssessmentBlocked() {
        given(userRepository.findById(9L)).willReturn(Optional.of(guestUser()));

        assertThatThrownBy(() -> limiter.checkDaily(UsageKind.ASSESSMENT, 9L, 1))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GUEST_LINK_REQUIRED);
    }

    @Test
    @DisplayName("게스트 - 차감은 고정 날짜 행에 쌓인다(날짜가 바뀌어도 리셋되지 않는 총량)")
    void recordDaily_guestUsesFixedDateRow() {
        given(userRepository.findById(9L)).willReturn(Optional.of(guestUser()));
        given(quotaRepository.findByUserIdAndKind(9L, UsageKind.CHAT)).willReturn(Optional.empty());

        limiter.recordDaily(UsageKind.CHAT, 9L, 1);

        verify(quotaRepository).recordUsage(9L, "CHAT", GUEST_DATE, 1);
    }

    @Test
    @DisplayName("한도 검사 - 무료 한도 소진 + 이용권 없음이면 QUOTA_EXCEEDED")
    void checkDaily_exceeded() {
        given(quotaRepository.findByUserIdAndKind(1L, UsageKind.ASSESSMENT))
                .willReturn(Optional.of(quota(TODAY, 3)));
        given(entitlementRepository.remainingOf(1L, UsageKind.ASSESSMENT)).willReturn(0L);

        assertThatThrownBy(() -> limiter.checkDaily(UsageKind.ASSESSMENT, 1L, 1))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.QUOTA_EXCEEDED);
    }

    @Test
    @DisplayName("한도 검사 - 무료 한도를 다 썼어도 이용권이 남아 있으면 통과한다")
    void checkDaily_paidEntitlementPasses() {
        given(quotaRepository.findByUserIdAndKind(1L, UsageKind.ASSESSMENT))
                .willReturn(Optional.of(quota(TODAY, 3)));
        given(entitlementRepository.remainingOf(1L, UsageKind.ASSESSMENT)).willReturn(2L);

        assertThatCode(() -> limiter.checkDaily(UsageKind.ASSESSMENT, 1L, 1))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("한도 검사 - 행의 날짜가 지났으면(자정 경과) 한도가 차 있어도 통과한다(리셋 대상)")
    void checkDaily_staleRowPasses() {
        given(quotaRepository.findByUserIdAndKind(1L, UsageKind.ASSESSMENT))
                .willReturn(Optional.of(quota(TODAY.minusDays(1), 3)));

        assertThatCode(() -> limiter.checkDaily(UsageKind.ASSESSMENT, 1L, 1))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("한도 검사 - 오늘 처음이면(행 없음) 통과한다")
    void checkDaily_firstUsePasses() {
        given(quotaRepository.findByUserIdAndKind(1L, UsageKind.CHAT))
                .willReturn(Optional.empty());

        assertThatCode(() -> limiter.checkDaily(UsageKind.CHAT, 1L, 1))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("사용 기록 - 무료 한도가 남아 있으면 오늘 날짜(KST)로 원자 upsert를 호출한다")
    void recordDaily_delegatesAtomicUpsert() {
        given(quotaRepository.findByUserIdAndKind(1L, UsageKind.CHAT))
                .willReturn(Optional.empty());

        limiter.recordDaily(UsageKind.CHAT, 1L, 1);

        verify(quotaRepository).recordUsage(eq(1L), eq("CHAT"), eq(TODAY), eq(1));
    }

    @Test
    @DisplayName("사용 기록 - 무료 한도 소진 시 이용권에서 차감하고 무료 카운터는 건드리지 않는다")
    void recordDaily_consumesEntitlementAfterFreeLimit() {
        given(quotaRepository.findByUserIdAndKind(1L, UsageKind.ASSESSMENT))
                .willReturn(Optional.of(quota(TODAY, 3)));
        given(entitlementRepository.findConsumableIds(1L, UsageKind.ASSESSMENT))
                .willReturn(List.of(10L));
        given(entitlementRepository.consumeOne(10L)).willReturn(1);

        limiter.recordDaily(UsageKind.ASSESSMENT, 1L, 1);

        verify(entitlementRepository).consumeOne(10L);
        verify(quotaRepository, never()).recordUsage(anyLong(), anyString(), any(), org.mockito.ArgumentMatchers.anyInt());
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

        limiter.recordDaily(UsageKind.ASSESSMENT, 1L, 1);

        verify(entitlementRepository).consumeOne(11L);
        verify(quotaRepository, never()).recordUsage(anyLong(), anyString(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("사용 기록 - 이용권 차감이 전부 실패하면 무료 카운터에 초과 기록으로 남긴다")
    void recordDaily_fallsBackToFreeCounter() {
        given(quotaRepository.findByUserIdAndKind(1L, UsageKind.ASSESSMENT))
                .willReturn(Optional.of(quota(TODAY, 3)));
        given(entitlementRepository.findConsumableIds(1L, UsageKind.ASSESSMENT))
                .willReturn(List.of());

        limiter.recordDaily(UsageKind.ASSESSMENT, 1L, 1);

        verify(quotaRepository).recordUsage(eq(1L), eq("ASSESSMENT"), eq(TODAY), eq(1));
    }

    @Test
    @DisplayName("한도 검사(배수) - 무료 잔여와 이용권 합이 요청 회수를 채우면 통과한다")
    void checkDaily_unitsAcrossFreeAndPaid() {
        // 무료 3 중 2 사용 = 잔여 1, 이용권 2 → 합 3
        given(quotaRepository.findByUserIdAndKind(1L, UsageKind.ASSESSMENT))
                .willReturn(Optional.of(quota(TODAY, 2)));
        given(entitlementRepository.remainingOf(1L, UsageKind.ASSESSMENT)).willReturn(2L);

        assertThatCode(() -> limiter.checkDaily(UsageKind.ASSESSMENT, 1L, 3))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.checkDaily(UsageKind.ASSESSMENT, 1L, 4))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.QUOTA_EXCEEDED);
    }

    @Test
    @DisplayName("사용 기록(배수) - 무료 잔여를 먼저 소진하고 부족분만 이용권에서 여러 번 차감한다")
    void recordDaily_unitsSplitFreeThenPaid() {
        // 무료 3 중 2 사용 = 잔여 1, 요청 3회 → 무료 1 기록 + 이용권 2회 차감
        given(quotaRepository.findByUserIdAndKind(1L, UsageKind.ASSESSMENT))
                .willReturn(Optional.of(quota(TODAY, 2)));
        given(entitlementRepository.findConsumableIds(1L, UsageKind.ASSESSMENT))
                .willReturn(List.of(10L));
        given(entitlementRepository.consumeOne(10L)).willReturn(1);

        limiter.recordDaily(UsageKind.ASSESSMENT, 1L, 3);

        verify(quotaRepository).recordUsage(eq(1L), eq("ASSESSMENT"), eq(TODAY), eq(1));
        verify(entitlementRepository, org.mockito.Mockito.times(2)).consumeOne(10L);
    }

    @Test
    @DisplayName("게스트(배수) - 총량 잔여가 요청 회수에 못 미치면 이용권 경로 없이 GUEST_LINK_REQUIRED")
    void checkDaily_guestUnitsExceedTotal() {
        given(userRepository.findById(9L)).willReturn(Optional.of(guestUser()));
        properties.setGuestChatTotalLimit(3);
        // 총량 3 중 2 사용 = 잔여 1, 요청 2회
        given(quotaRepository.findByUserIdAndKind(9L, UsageKind.CHAT))
                .willReturn(Optional.of(quota(GUEST_DATE, 2)));

        assertThatThrownBy(() -> limiter.checkDaily(UsageKind.CHAT, 9L, 2))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GUEST_LINK_REQUIRED);
        verify(entitlementRepository, never()).remainingOf(anyLong(), any());
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
    @DisplayName("생성 락 - 락 획득에 성공(반환 1)하면 통과한다")
    void acquireInFlight_acquired() {
        given(generationLockRepository.acquire(eq("CHAT:1"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(1);

        assertThatCode(() -> limiter.acquireInFlight(UsageKind.CHAT, 1L))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("생성 락 - 유효한 락이 이미 있어(반환 0) 획득 실패면 GENERATION_IN_PROGRESS")
    void acquireInFlight_alreadyLocked() {
        given(generationLockRepository.acquire(eq("ASSESSMENT:1"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(0);

        assertThatThrownBy(() -> limiter.acquireInFlight(UsageKind.ASSESSMENT, 1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GENERATION_IN_PROGRESS);
    }

    @Test
    @DisplayName("생성 락 - 종류별 TTL을 만료 시각에 반영한다(진단이 채팅보다 길다)")
    void acquireInFlight_ttlPerKind() {
        var untilCaptor = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        var nowCaptor = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        given(generationLockRepository.acquire(anyString(), nowCaptor.capture(), untilCaptor.capture()))
                .willReturn(1);

        limiter.acquireInFlight(UsageKind.ASSESSMENT, 1L);

        long ttlSeconds = java.time.Duration.between(nowCaptor.getValue(), untilCaptor.getValue()).getSeconds();
        assertThat(ttlSeconds).isEqualTo(properties.getAssessmentLockTtlSeconds());   // 70초(> 채팅 20)
    }

    @Test
    @DisplayName("생성 락 - 해제는 리포지토리 release에 위임한다")
    void releaseInFlight_delegates() {
        limiter.releaseInFlight(UsageKind.CHAT, 5L);

        verify(generationLockRepository).release("CHAT:5");
    }
}
