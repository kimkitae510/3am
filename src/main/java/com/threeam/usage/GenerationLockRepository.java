package com.threeam.usage;

import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GenerationLockRepository extends JpaRepository<GenerationLock, String> {

    // 락 획득을 원자 upsert 한 문장으로 한다. MySQL의 affected-rows 규칙을 판정에 쓴다:
    //  - 새 행 INSERT → 1 (획득 성공)
    //  - 만료된 락을 새 시각으로 UPDATE → 2 (뺏어서 획득 성공)
    //  - 아직 유효한 락이라 값이 그대로 → 0 (획득 실패)
    // 즉 반환 >= 1이면 획득. IF로 "만료됐을 때만" 값을 바꾸므로, 유효한 락은 손대지 않는다.
    // 날짜는 DB 타임존에 안 묶이게 앱(KST)에서 계산해 넘긴다.
    @Modifying
    @Query(value = """
            INSERT INTO generation_lock (lock_key, locked_until)
            VALUES (:key, :until)
            ON DUPLICATE KEY UPDATE
                locked_until = IF(locked_until < :now, :until, locked_until)
            """, nativeQuery = true)
    int acquire(@Param("key") String key, @Param("now") LocalDateTime now,
                @Param("until") LocalDateTime until);

    // 정상 종료 시 즉시 반납. 실패로 못 부르면 locked_until 만료로 자동 해제된다(좀비 락 방지).
    @Modifying
    @Query(value = "DELETE FROM generation_lock WHERE lock_key = :key", nativeQuery = true)
    void release(@Param("key") String key);
}
