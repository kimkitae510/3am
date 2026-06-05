package com.threeam.payment.client;

import com.threeam.payment.entity.PaymentItem;
import java.util.concurrent.CompletableFuture;

// PG 호출 추상화. 구현체(Mock/토스/패들)를 설정(payment.provider)으로 갈아끼운다.
//
// 완료 규약이 상태 머신의 핵심이다:
// - 정상 완료 = PG의 실제 상태를 아는 것. 거절(FAILED)도 "확실히 안 됐다"는 앎이라 정상 완료다.
// - 예외 완료 = 결과 불명(타임아웃, 5xx, 네트워크). 호출부는 상태를 확정하지 말고
//   IN_PROGRESS/CANCEL_REQUESTED로 남겨 재동기화에 맡겨야 한다.
public interface PaymentGateway {

    // 결제창을 띄우기 전에 PG 쪽에 주문을 만들어 두고 그 식별자를 받아온다.
    //
    // 토스처럼 결제창 인증 뒤에 식별자가 생기는 PG는 null을 준다(할 일 없음). 패들처럼
    // 결제창에서 결제가 끝나버리는 PG는 여기서 반드시 식별자를 받아 저장해야 한다 —
    // 유저가 결제 직후 창을 닫아 프론트가 결과를 못 알려도, 저장된 식별자로 재조회해
    // 지급을 복구할 수 있다. 식별자가 없으면 돈만 받고 지급이 사라지는 경로가 생긴다.
    CompletableFuture<String> prepare(String orderId, PaymentItem item, int amount);

    // 결제 승인. 토스는 이 호출이 성공해야 실제로 돈이 움직인다(위젯 단계는 인증까지만).
    // 패들은 결제창에서 이미 돈이 움직인 뒤라 "실상태 확인"의 의미가 된다.
    CompletableFuture<PgPaymentResult> confirm(String paymentKey, String orderId, int amount);

    // 취소(환불). idempotencyKey가 같으면 PG가 중복 실행을 막는다 — 불명 후 재시도가 안전한 근거.
    // refundAccount는 가상계좌 입금 후 환불에만 필요(그 외 null).
    CompletableFuture<PgPaymentResult> cancel(String paymentKey, int cancelAmount, String reason,
                                              String idempotencyKey, RefundAccount refundAccount);

    // 주문 기준 실상태 조회. 웹훅과 재동기화의 진실 원천 — 웹훅 페이로드는 믿지 않고 이걸로 재확인한다.
    // paymentKey는 orderId로 조회할 수 없는 PG(패들)를 위해 함께 넘긴다. 토스는 쓰지 않는다.
    CompletableFuture<PgPaymentResult> findByOrderId(String orderId, String paymentKey);

    record RefundAccount(String bank, String accountNumber, String holderName) {
    }
}
