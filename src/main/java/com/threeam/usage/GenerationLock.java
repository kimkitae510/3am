package com.threeam.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 생성(대화 답변, 진단) 동시 실행을 막는 DB 분산 락. 인메모리 잠금을 대체한다 —
// 재시작으로 날아가거나 멀티인스턴스에서 각자 따로 세는 문제를 없앤다.
// locked_until(만료 시각)로 TTL을 준다: LLM 실패로 락을 못 풀어도 이 시각이 지나면
// 풀린 것으로 취급해 좀비 락이 남지 않는다. 락은 유저+종류 단위(lock_key)로,
// 한 유저가 같은 종류를 동시에 두 건 생성하지 못하게 해 후차감 한도 초과(TOCTOU)까지 막는다.
@Entity
@Table(name = "generation_lock")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GenerationLock {

    @Id
    @Column(name = "lock_key", length = 60)
    private String lockKey;

    @Column(name = "locked_until", nullable = false)
    private LocalDateTime lockedUntil;
}
