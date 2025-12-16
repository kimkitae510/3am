package com.threeam.payment.client;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// 개발 스텁. PG 키 없이 전체 결제 흐름(주문 → 승인 → 지급 → 환불)을 돌려볼 수 있게 한다.
// 무조건 승인/취소 성공으로 응답한다 — 실패, 불명 경로는 단위 테스트에서 검증한다.
@Slf4j
@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = "mock", matchIfMissing = true)
public class MockPaymentGateway implements PaymentGateway {

    @Override
    public CompletableFuture<PgPaymentResult> confirm(String paymentKey, String orderId, int amount) {
        log.info("[MockPG] 승인 orderId={} amount={}", orderId, amount);
        return CompletableFuture.completedFuture(new PgPaymentResult(
                paymentKey, orderId, PgStatus.DONE, "간편결제(모의)", LocalDateTime.now(), null, 0, null));
    }

    @Override
    public CompletableFuture<PgPaymentResult> cancel(String paymentKey, int cancelAmount, String reason,
                                                     String idempotencyKey, RefundAccount refundAccount) {
        log.info("[MockPG] 취소 paymentKey={} cancelAmount={}", paymentKey, cancelAmount);
        return CompletableFuture.completedFuture(new PgPaymentResult(
                paymentKey, null, PgStatus.PARTIAL_CANCELED, null, null, null, cancelAmount, null));
    }

    @Override
    public CompletableFuture<PgPaymentResult> findByOrderId(String orderId) {
        // 모의 결제는 응답 유실이 없어 재동기화가 개입할 일이 없다. 승인 완료로 간주한다.
        return CompletableFuture.completedFuture(PgPaymentResult.of(null, orderId, PgStatus.DONE));
    }
}
