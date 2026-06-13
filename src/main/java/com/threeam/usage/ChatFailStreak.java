package com.threeam.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 채팅 LLM 연속 실패 카운터. 실패는 후차감(미차감)이라, 막지 않으면 같은 유저가 실패할 때마다
// 무료로 LLM을 계속 부를 수 있다 — 분석에는 같은 가드가 story 단위로 이미 있다.
// 유저 단위인 이유: 생성 락과 범위를 맞춘다. 방(story) 단위로 두면 방을 갈아타며 가드를 지나칠 수 있다.
// 답변이 한 번이라도 저장되면 행을 지운다 — 그래서 streak는 언제나 '연속' 실패 수다.
@Entity
@Table(name = "chat_fail_streak")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatFailStreak {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private int streak;

    @Column(name = "last_failed_at", nullable = false)
    private LocalDateTime lastFailedAt;
}
