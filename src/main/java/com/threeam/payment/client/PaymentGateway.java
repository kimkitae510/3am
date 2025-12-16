package com.threeam.payment.client;

import java.util.concurrent.CompletableFuture;

// PG 호출 추상화. 구현체(Mock/토스)를 설정(payment.provider)으로 갈아끼운다.
//
// 완료 규약이 상태 머신의 핵심이다:
// - 정상 완료 = PG의 실제 상태를 아는 것. 거절(FAILED)도 "확실히 안 됐다"는 앎이라 정상 완료다.
// - 예외 완료 = 결과 불명(타임아웃, 5xx, 네트워크). 호출부는 상태를 확정하지 말고
//   IN_PROGRESS/CANCEL_REQUESTED로 남겨 재동기화에 맡겨야 한다.
public interface PaymentGateway {

    // 결제 승인. 이 호출이 성공해야 실제로 돈이 움직인다(위젯 단계는 인증까지만).
    CompletableFuture<PgPaymentResult> confirm(String paymentKey, String orderId, int amount);

    // 취소(환불). idempotencyKey가 같으면 PG가 중복 실행을 막는다 — 불명 후 재시도가 안전한 근거.
    // refundAccount는 가상계좌 입금 후 환불에만 필요(그 외 null).
    CompletableFuture<PgPaymentResult> cancel(String paymentKey, int cancelAmount, String reason,
                                              String idempotencyKey, RefundAccount refundAccount);

    // 주문 기준 실상태 조회. 웹훅과 재동기화의 진실 원천 — 웹훅 페이로드는 믿지 않고 이걸로 재확인한다.
    CompletableFuture<PgPaymentResult> findByOrderId(String orderId);

    record RefundAccount(String bank, String accountNumber, String holderName) {
    }
}
