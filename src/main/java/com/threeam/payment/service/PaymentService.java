package com.threeam.payment.service;

import com.threeam.consent.service.ConsentService;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.payment.client.PaymentGateway;
import com.threeam.payment.client.PaymentProperties;
import com.threeam.payment.client.PgStatus;
import com.threeam.payment.dto.CancelRequest;
import com.threeam.payment.dto.ConfirmRequest;
import com.threeam.payment.dto.OrderCreateRequest;
import com.threeam.payment.dto.OrderCreateResponse;
import com.threeam.payment.dto.PaymentConfigResponse;
import com.threeam.payment.dto.PaymentResponse;
import com.threeam.payment.entity.Payment;
import com.threeam.payment.entity.PaymentItem;
import com.threeam.payment.entity.PaymentStatus;
import com.threeam.usage.Entitlement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 결제 오케스트레이션. 트랜잭션 밖(NOT_SUPPORTED)에서 느린 PG 호출을 논블로킹으로 다루고,
// DB 반영은 PaymentTxService의 짧은 트랜잭션으로 위임한다 — 진단(LLM)과 같은 구조.
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentTxService txService;
    private final PaymentGateway paymentGateway;
    private final PaymentProperties properties;
    private final ConsentService consentService;

    public PaymentConfigResponse config() {
        return new PaymentConfigResponse(properties.getProvider(), properties.getToss().getClientKey());
    }

    // 금액은 여기서(서버 상품 정의로) 확정된다. 프론트는 orderId와 금액을 받아 위젯만 띄운다.
    public OrderCreateResponse createOrder(Long userId, OrderCreateRequest request) {
        // 청약철회 제한은 결제 전 동의가 있어야 성립한다(전자상거래법) — 동의 없인 주문 자체를 안 만든다.
        if (!request.isRefundPolicyAgreed()) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
        }
        PaymentItem item = PaymentItem.parse(request.getItem());
        Payment payment = txService.createOrder(userId, item, properties.getMaxPendingOrdersPerUser());
        // 주문과 동의를 묶어 증빙으로 남긴다. 여기 도달했다는 것 자체가 동의 후이므로 순서는 안전.
        consentService.recordPurchaseConsent(userId, payment.getOrderId());
        return OrderCreateResponse.from(payment);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CompletableFuture<PaymentResponse> confirm(Long userId, ConfirmRequest request) {
        Payment payment = txService.loadOwned(userId, request.getOrderId());

        // 멱등: successUrl 새로고침, 버튼 연타로 다시 와도 이미 처리된 결과를 그대로 준다.
        if (payment.getStatus() == PaymentStatus.DONE
                || payment.getStatus() == PaymentStatus.WAITING_FOR_DEPOSIT) {
            return CompletableFuture.completedFuture(
                    txService.toResponseOwned(userId, request.getOrderId()));
        }
        if (payment.getStatus() == PaymentStatus.IN_PROGRESS) {
            // 직전 승인의 결과 불명 구간 — 여기서 또 쏘면 이중 승인 위험. 재동기화가 확정한다.
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSING);
        }
        if (payment.getStatus() != PaymentStatus.READY) {
            throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATE);
        }
        // 위변조 관문: 프론트가 가져온 금액이 주문 시 서버가 확정한 금액과 다르면 승인 자체를 안 한다.
        if (request.getAmount() != payment.getAmount()) {
            log.warn("결제 금액 불일치 orderId={} 주문={} 요청={}",
                    request.getOrderId(), payment.getAmount(), request.getAmount());
            throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
        if (!txService.claimConfirm(request.getOrderId(), request.getPaymentKey())) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSING);
        }

        return paymentGateway.confirm(request.getPaymentKey(), request.getOrderId(), payment.getAmount())
                .thenApply(result -> {
                    PaymentResponse response = txService.applyPgResult(request.getOrderId(), result);
                    if (result.status() == PgStatus.FAILED) {
                        throw new BusinessException(ErrorCode.PAYMENT_CONFIRM_REJECTED);
                    }
                    return response;
                })
                .exceptionally(this::rethrowOrPending);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CompletableFuture<PaymentResponse> cancel(Long userId, String orderId, CancelRequest request) {
        Payment payment = txService.loadOwned(userId, orderId);

        if (payment.getStatus() == PaymentStatus.CANCELED) {
            return CompletableFuture.completedFuture(txService.toResponseOwned(userId, orderId)); // 멱등
        }
        if (payment.getStatus() == PaymentStatus.CANCEL_REQUESTED) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSING);
        }
        if (payment.getStatus() != PaymentStatus.DONE
                && payment.getStatus() != PaymentStatus.WAITING_FOR_DEPOSIT) {
            throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATE);
        }

        int refundAmount = resolveRefundAmount(payment, request);
        String reason = request.getReason() == null || request.getReason().isBlank()
                ? "사용자 요청" : request.getReason();
        PaymentStatus from = payment.getStatus();
        if (!txService.claimCancel(orderId, from, reason)) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSING);
        }

        // 멱등키 = 주문 + 시도 번호. 같은 시도의 재전송은 PG가 최초 1회로 흡수하고(이중 환불 방지),
        // 거절 후 정보를 고쳐 다시 요청하는 "새 시도"는 새 키를 받는다(캐시된 거절 응답 회피).
        String idempotencyKey = orderId + "-cancel-" + txService.cancelAttemptsOf(orderId);
        return paymentGateway.cancel(payment.getPaymentKey(), refundAmount, reason,
                        idempotencyKey, toRefundAccount(request))
                .thenApply(result -> {
                    if (result.status() == PgStatus.FAILED) {
                        // 확실한 거절만 원상 복귀. 불명이면 여기 오지 않는다(exceptionally → 재동기화).
                        txService.revertCancelRequest(orderId, from);
                        throw new BusinessException(ErrorCode.PAYMENT_CANCEL_REJECTED);
                    }
                    return txService.applyPgResult(orderId, result);
                })
                .exceptionally(this::rethrowOrPending);
    }

    // 웹훅과 재동기화의 공용 경로. 페이로드, 추측이 아니라 PG 조회 API가 준 실상태만 반영한다.
    public CompletableFuture<Void> syncByOrderId(String orderId) {
        var status = txService.statusOf(orderId);
        if (status.isEmpty()) {
            // 우리 주문이 아니다(다른 환경의 웹훅 등). 재시도를 부르지 않게 조용히 넘긴다.
            log.warn("알 수 없는 주문 동기화 요청 무시 orderId={}", orderId);
            return CompletableFuture.completedFuture(null);
        }
        if (status.get().isTerminal()) {
            // 이미 종결(실패, 만료, 취소)된 주문은 어떤 이벤트로도 안 바뀐다 — PG를 다시 조회할 필요가 없다.
            return CompletableFuture.completedFuture(null);
        }
        return paymentGateway.findByOrderId(orderId)
                .thenAccept(result -> txService.applyPgResult(orderId, result));
    }

    public List<PaymentResponse> myPayments(Long userId) {
        return txService.myPayments(userId);
    }

    public PaymentResponse myPayment(Long userId, String orderId) {
        return txService.toResponseOwned(userId, orderId);
    }

    private int resolveRefundAmount(Payment payment, CancelRequest request) {
        // 입금 전 가상계좌: 나간 돈이 없으니 주문 자체를 접는 것 — 명목 금액으로 취소만 요청한다.
        if (payment.getStatus() == PaymentStatus.WAITING_FOR_DEPOSIT) {
            return payment.getAmount();
        }
        List<Entitlement> entitlements = txService.entitlementsOf(payment.getId());
        if (entitlements.isEmpty()) {
            throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATE);
        }
        // 정책: 한 번이라도 쓴 결제는 환불 불가, 전량 미사용이면 전액 환불.
        // 부분 환불(회당 가치 가중)은 폐지 — 계산과 안내가 단순해지고 "몇 % 쓰면 얼마" 분쟁 여지가 없다.
        boolean anyUsed = entitlements.stream().anyMatch(e -> e.getUsedCount() > 0);
        if (anyUsed) {
            throw new BusinessException(ErrorCode.REFUND_NOT_ALLOWED);
        }
        int refundAmount = payment.getAmount();
        // 가상계좌로 이미 입금된 돈은 돌려보낼 계좌가 있어야 한다(카드, 간편결제는 수단으로 자동 환불).
        if ("가상계좌".equals(payment.getMethod()) && toRefundAccount(request) == null) {
            throw new BusinessException(ErrorCode.REFUND_ACCOUNT_REQUIRED);
        }
        return refundAmount;
    }

    private PaymentGateway.RefundAccount toRefundAccount(CancelRequest request) {
        if (isBlank(request.getRefundBank()) || isBlank(request.getRefundAccount())
                || isBlank(request.getRefundHolder())) {
            return null;
        }
        return new PaymentGateway.RefundAccount(
                request.getRefundBank(), request.getRefundAccount(), request.getRefundHolder());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // 불명(게이트웨이 예외)과 확정 실패(BusinessException)를 여기서 가른다.
    // 불명은 상태를 그대로 두고 "확인 지연"으로 알린다 — 재동기화가 곧 실결과로 확정한다.
    private PaymentResponse rethrowOrPending(Throwable ex) {
        Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
        if (cause instanceof BusinessException business) {
            throw business;
        }
        log.error("PG 응답 불명 — 상태 보존, 재동기화 대기", cause);
        throw new BusinessException(ErrorCode.PAYMENT_RESULT_PENDING);
    }
}
