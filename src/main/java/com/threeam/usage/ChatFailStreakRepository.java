package com.threeam.usage;

import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatFailStreakRepository extends JpaRepository<ChatFailStreak, Long> {

    // 실패 기록을 원자 upsert 한 문장으로. 첫 실패는 1, 이어지는 실패는 +1이다.
    // 조회 후 증가로 나누면 동시 실패(멀티인스턴스, 콜백 스레드 여럿)에서 카운트가 유실된다.
    @Modifying
    @Query(value = """
            INSERT INTO chat_fail_streak (user_id, streak, last_failed_at)
            VALUES (:userId, 1, :now)
            ON DUPLICATE KEY UPDATE
                streak = streak + 1,
                last_failed_at = :now
            """, nativeQuery = true)
    void markFailed(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    // 답변이 저장되면 연속이 끊긴 것 — 행째로 지운다(성공한 유저의 행을 남겨둘 이유가 없다).
    @Modifying
    @Query(value = "DELETE FROM chat_fail_streak WHERE user_id = :userId", nativeQuery = true)
    void clear(@Param("userId") Long userId);
}
