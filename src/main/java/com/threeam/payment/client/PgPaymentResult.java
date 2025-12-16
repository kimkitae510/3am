package com.threeam.payment.client;

import java.time.LocalDateTime;

// PG 응답을 우리 어휘로 옮긴 결과. 상태 반영(applyPgResult)의 유일한 입력이다.
public record PgPaymentResult(
        String paymentKey,
        String orderId,
        PgStatus status,
        String method,
        LocalDateTime approvedAt,
        String failReason,
        int canceledAmount,
        VirtualAccount virtualAccount
) {
    public record VirtualAccount(String bank, String accountNumber, LocalDateTime dueAt) {
    }

    public static PgPaymentResult of(String paymentKey, String orderId, PgStatus status) {
        return new PgPaymentResult(paymentKey, orderId, status, null, null, null, 0, null);
    }
}
