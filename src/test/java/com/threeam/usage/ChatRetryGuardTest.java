package com.threeam.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ChatRetryGuardTest {

    @Mock
    private ChatFailStreakRepository repository;

    // 기본값(3회 / 60초)을 그대로 쓴다 — 상한을 테스트에서 바꾸면 설정과 어긋나도 안 드러난다.
    private ChatRetryGuard guard;

    @BeforeEach
    void setUp() {
        guard = new ChatRetryGuard(new UsageProperties(), repository);
    }

    @Test
    @DisplayName("실패 이력이 없으면 차단하지 않는다")
    void noRecord() {
        given(repository.findById(1L)).willReturn(Optional.empty());

        assertThat(guard.blockedSeconds(1L)).isZero();
    }

    @Test
    @DisplayName("연속 실패가 상한 미만이면 차단하지 않는다 - 일시 장애는 재시도로 풀린다")
    void belowLimit() {
        given(repository.findById(1L)).willReturn(Optional.of(streak(2, LocalDateTime.now())));

        assertThat(guard.blockedSeconds(1L)).isZero();
    }

    @Test
    @DisplayName("연속 3회 실패 직후면 남은 초를 돌려준다(올림)")
    void blockedWithRemaining() {
        given(repository.findById(1L))
                .willReturn(Optional.of(streak(3, LocalDateTime.now().minusSeconds(20))));

        // 쿨다운 60초 - 경과 20초 = 40초 근처. 테스트 실행 지연을 감안해 범위로 본다.
        assertThat(guard.blockedSeconds(1L)).isBetween(39, 40);
    }

    @Test
    @DisplayName("쿨다운이 지나면 새 대화 없이도 다시 열어준다")
    void cooldownExpired() {
        given(repository.findById(1L))
                .willReturn(Optional.of(streak(5, LocalDateTime.now().minusSeconds(61))));

        assertThat(guard.blockedSeconds(1L)).isZero();
    }

    // 엔티티는 네이티브 upsert로만 써지므로 생성자가 없다 — 테스트에서만 필드를 직접 채운다.
    private ChatFailStreak streak(int count, LocalDateTime lastFailedAt) {
        ChatFailStreak entity = new ChatFailStreak();
        ReflectionTestUtils.setField(entity, "userId", 1L);
        ReflectionTestUtils.setField(entity, "streak", count);
        ReflectionTestUtils.setField(entity, "lastFailedAt", lastFailedAt);
        return entity;
    }
}
