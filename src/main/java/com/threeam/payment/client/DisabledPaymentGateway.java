package com.threeam.payment.client;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.payment.entity.PaymentItem;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// PG 심사가 끝나 실키를 받기 전까지 결제만 닫아두는 상태.
// mock은 무단 승인이라 운영에 못 올리고(ProductionReadinessGuard가 부팅을 세운다),
// toss는 키가 없으면 프론트 위젯이 깨진다. 그 사이를 메우는 구현체다.
//
// 주문 자체를 PaymentService가 먼저 막으므로 여기까지 오는 일은 없어야 한다 —
// 그래도 뚫고 들어온 경로가 있으면 조용히 승인되지 않도록 전부 거절한다.
@Slf4j
@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = "disabled")
public class DisabledPaymentGateway implements PaymentGateway {

    @Override
    public CompletableFuture<String> prepare(String orderId, PaymentItem item, int amount) {
        log.warn("[결제 비활성] 주문 준비 시도 차단 orderId={}", orderId);
        return CompletableFuture.failedFuture(new BusinessException(ErrorCode.PAYMENT_DISABLED));
    }

    @Override
    public CompletableFuture<PgPaymentResult> confirm(String paymentKey, String orderId, int amount) {
        log.warn("[결제 비활성] 승인 시도 차단 orderId={}", orderId);
        return CompletableFuture.failedFuture(new BusinessException(ErrorCode.PAYMENT_DISABLED));
    }

    @Override
    public CompletableFuture<PgPaymentResult> cancel(String paymentKey, int cancelAmount, String reason,
                                                     String idempotencyKey, RefundAccount refundAccount) {
        log.warn("[결제 비활성] 취소 시도 차단 paymentKey={}", paymentKey);
        return CompletableFuture.failedFuture(new BusinessException(ErrorCode.PAYMENT_DISABLED));
    }

    @Override
    public CompletableFuture<PgPaymentResult> findByOrderId(String orderId, String paymentKey) {
        // 재동기화 스케줄러가 주기적으로 부른다. 예외를 던지면 매분 에러 로그가 쌓이므로
        // UNKNOWN으로 "아무것도 확정하지 말라"만 알린다(mock의 조회와 같은 취지).
        return CompletableFuture.completedFuture(
                PgPaymentResult.of(null, orderId, PgStatus.UNKNOWN));
    }
}
