package com.threeam.usage;

import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 채팅 연속 실패 가드. 진단의 같은 가드(AssessmentTxService)와 짝이며, 차이는 두 가지다:
//  - 범위가 story가 아니라 유저다(생성 락과 같은 범위 — 방을 갈아타는 우회를 막는다).
//  - "같은 재료" 판정이 없다. 답변이 저장되면 행을 지우므로 streak 자체가 연속 실패 수다.
@Component
@RequiredArgsConstructor
public class ChatRetryGuard {

    private final UsageProperties properties;
    private final ChatFailStreakRepository repository;

    // 재시도까지 남은 초. 0이면 차단 아님 — 화면의 카운트다운이 이 값을 쓴다.
    @Transactional(readOnly = true)
    public int blockedSeconds(Long userId) {
        ChatFailStreak streak = repository.findById(userId).orElse(null);
        if (streak == null || streak.getStreak() < properties.getChatFailStreakLimit()) {
            return 0;
        }
        LocalDateTime retryableAt = streak.getLastFailedAt()
                .plusSeconds(properties.getChatFailCooldownSeconds());
        LocalDateTime now = LocalDateTime.now();
        if (!retryableAt.isAfter(now)) {
            return 0;
        }
        // 올림 — 1.2초 남았는데 1초로 내려주면 화면이 0을 찍은 뒤에도 서버가 아직 막는다.
        return (int) Math.ceil(Duration.between(now, retryableAt).toMillis() / 1000.0);
    }

    // 아래 둘은 LLM 콜백에서 불린다. 예외는 여기서 삼키지 않는다 — 트랜잭션 안에서 잡으면
    // rollback-only로 표시된 채 커밋에 들어가 어차피 터진다. 호출부가 트랜잭션 밖에서 격리한다.
    @Transactional
    public void markFailed(Long userId) {
        repository.markFailed(userId, LocalDateTime.now());
    }

    @Transactional
    public void clear(Long userId) {
        repository.clear(userId);
    }
}
