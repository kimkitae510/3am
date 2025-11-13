package com.threeam.usage;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsageQuotaRepository extends JpaRepository<UsageQuota, Long> {

    Optional<UsageQuota> findByUserIdAndKind(Long userId, UsageKind kind);

    // 첫 사용(INSERT), 같은 날 증가, 날짜가 바뀐 뒤 리셋(1부터)을 한 문장으로 처리한다.
    // 두 단계(SELECT 후 UPDATE)로 나누면 자정 직후 동시 요청이 둘 다 리셋하는 레이스가 생기므로
    // MySQL의 행 락에 직렬화를 맡긴다. 날짜는 DB 타임존에 묶이지 않게 앱(KST)에서 계산해 넘긴다.
    @Modifying
    @Query(value = """
            INSERT INTO usage_quota (user_id, kind, quota_date, used_count)
            VALUES (:userId, :kind, :today, 1)
            ON DUPLICATE KEY UPDATE
                used_count = IF(quota_date = :today, used_count + 1, 1),
                quota_date = :today
            """, nativeQuery = true)
    void recordUsage(@Param("userId") Long userId, @Param("kind") String kind,
                     @Param("today") LocalDate today);
}
