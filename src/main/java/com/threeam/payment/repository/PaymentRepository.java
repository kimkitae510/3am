package com.threeam.payment.repository;

import com.threeam.payment.entity.Payment;
import com.threeam.payment.entity.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderId(String orderId);

    // PG 결과 반영은 행 락을 잡고 한다 — 승인 응답, 웹훅, 재동기화가 같은 결제에 동시에
    // 도착해도 전이가 한 줄로 직렬화된다(이용권 지급은 유니크 키가 한 번 더 막는다).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.orderId = :orderId")
    Optional<Payment> findByOrderIdForUpdate(@Param("orderId") String orderId);

    List<Payment> findByUserIdOrderByCreatedAtDesc(Long userId);

    // 미결 주문 도배 방어용. 만료 전(만료 스케줄러가 아직 안 돈) READY 주문 수를 센다.
    int countByUserIdAndStatus(Long userId, PaymentStatus status);

    // 상태 전이를 조건부 UPDATE 한 문장으로 — 같은 주문의 동시 요청 중 한 쪽만 성공한다(선점).
    // updated_at은 재동기화의 "얼마나 머물렀나" 기준이라 벌크 UPDATE에서도 직접 갱신한다.
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE payments SET status = :to, payment_key = :paymentKey, updated_at = NOW(6)
            WHERE order_id = :orderId AND status = :from
            """, nativeQuery = true)
    int claimWithKey(@Param("orderId") String orderId, @Param("from") String from,
                     @Param("to") String to, @Param("paymentKey") String paymentKey);

    // cancel_attempts 증가는 PG 멱등키 갱신용 — 시도마다 새 키, 같은 시도의 재전송은 같은 키.
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE payments SET status = :to, cancel_reason = :reason,
                cancel_attempts = cancel_attempts + 1, updated_at = NOW(6)
            WHERE order_id = :orderId AND status = :from
            """, nativeQuery = true)
    int claimCancel(@Param("orderId") String orderId, @Param("from") String from,
                    @Param("to") String to, @Param("reason") String reason);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE payments SET status = :to, updated_at = NOW(6)
            WHERE order_id = :orderId AND status = :from
            """, nativeQuery = true)
    int transition(@Param("orderId") String orderId, @Param("from") String from, @Param("to") String to);

    // 재동기화 대상 조회. 배치당 상한을 둬 스케줄 한 턴이 무한정 길어지지 않게 한다.
    List<Payment> findTop20ByStatusAndUpdatedAtBefore(PaymentStatus status, LocalDateTime before);

    List<Payment> findTop20ByStatusAndCreatedAtBefore(PaymentStatus status, LocalDateTime before);

    List<Payment> findTop20ByStatusAndVbankDueAtBefore(PaymentStatus status, LocalDateTime before);
}
