package com.threeam.payment.service;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.payment.client.PgPaymentResult;
import com.threeam.payment.dto.PaymentResponse;
import com.threeam.payment.entity.Payment;
import com.threeam.payment.entity.PaymentItem;
import com.threeam.payment.entity.PaymentStatus;
import com.threeam.payment.repository.PaymentRepository;
import com.threeam.usage.Entitlement;
import com.threeam.usage.EntitlementRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 결제의 DB 작업만 모은 짧은 트랜잭션 계층. 느린 PG 호출은 PaymentService(트랜잭션 밖)가 하고,
// 여기는 호출 전후의 상태 전이만 빠르게 처리한다 — LLM의 AssessmentTxService와 같은 분리.
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTxService {

    private final PaymentRepository paymentRepository;
    private final EntitlementRepository entitlementRepository;

    @Transactional
    public Payment createOrder(Long userId, PaymentItem item) {
        Payment payment = Payment.builder()
                .userId(userId)
                .orderId(UUID.randomUUID().toString())
                .item(item)
                .build();
        return paymentRepository.save(payment);
    }

    @Transactional(readOnly = true)
    public Payment loadOwned(Long userId, String orderId) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        // 남의 결제는 존재 자체를 숨긴다(403이 아니라 404) — orderId 추측 탐색 차단.
        if (!payment.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_FOUND);
        }
        return payment;
    }

    // READY → IN_PROGRESS 선점. 조건부 UPDATE라 동시 승인 요청 중 한 쪽만 true를 받는다.
    @Transactional
    public boolean claimConfirm(String orderId, String paymentKey) {
        return paymentRepository.claimWithKey(orderId,
                PaymentStatus.READY.name(), PaymentStatus.IN_PROGRESS.name(), paymentKey) == 1;
    }

    @Transactional
    public boolean claimCancel(String orderId, PaymentStatus from, String reason) {
        return paymentRepository.claimCancel(orderId,
                from.name(), PaymentStatus.CANCEL_REQUESTED.name(), reason) == 1;
    }

    // 취소가 PG에서 확실히 거절됐을 때 원래 상태로 복귀시킨다(불명이면 복귀 금지 — 재동기화 몫).
    @Transactional
    public boolean revertCancelRequest(String orderId, PaymentStatus backTo) {
        return paymentRepository.transition(orderId,
                PaymentStatus.CANCEL_REQUESTED.name(), backTo.name()) == 1;
    }

    @Transactional
    public boolean expire(String orderId) {
        return paymentRepository.transition(orderId,
                PaymentStatus.READY.name(), PaymentStatus.EXPIRED.name()) == 1;
    }

    @Transactional(readOnly = true)
    public Optional<PaymentStatus> statusOf(String orderId) {
        return paymentRepository.findByOrderId(orderId).map(Payment::getStatus);
    }

    // PG의 실상태를 우리 기록에 반영하는 유일한 창구. 승인 응답, 취소 응답, 웹훅, 재동기화가
    // 전부 여기로 모인다 — 상태 전이 규칙이 한 곳에만 있어야 경로별로 어긋나지 않는다.
    // 행 락으로 동시 반영을 직렬화하고, 몇 번을 다시 실행해도 같은 결과가 되도록(멱등) 짠다.
    @Transactional
    public PaymentResponse applyPgResult(String orderId, PgPaymentResult result) {
        Payment payment = paymentRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        // 종결된 결제는 웹훅 재전송, 뒤늦은 재동기화가 와도 되살리지 않는다.
        if (payment.getStatus().isTerminal()) {
            return toResponse(payment);
        }

        switch (result.status()) {
            case DONE -> {
                if (payment.getStatus() != PaymentStatus.DONE) {
                    payment.markDone(result.method(),
                            result.approvedAt() != null ? result.approvedAt() : LocalDateTime.now());
                }
                // 승인 확정과 이용권 지급이 한 트랜잭션 — 돈만 받고 지급이 빠지는 틈을 없앤다.
                grantEntitlementOnce(payment);
            }
            case WAITING_FOR_DEPOSIT -> {
                if (payment.getStatus() == PaymentStatus.READY
                        || payment.getStatus() == PaymentStatus.IN_PROGRESS) {
                    PgPaymentResult.VirtualAccount va = result.virtualAccount();
                    payment.markWaitingForDeposit(result.method(),
                            va == null ? null : va.bank(),
                            va == null ? null : va.accountNumber(),
                            va == null ? null : va.dueAt());
                }
            }
            // 우리 시나리오의 부분취소는 "미사용분 환불로 종결"이라 둘 다 취소 완료로 접는다.
            case CANCELED, PARTIAL_CANCELED -> {
                payment.markCanceled(result.canceledAmount(), LocalDateTime.now());
                revokeEntitlement(payment);
            }
            case FAILED -> {
                if (payment.getStatus() == PaymentStatus.DONE) {
                    // 승인 완료된 결제에 거절 신호 — 정상 흐름엔 없는 조합. 기록만 남기고 상태는 지킨다.
                    log.error("DONE 결제에 FAILED 신호 무시 orderId={} reason={}", orderId, result.failReason());
                } else {
                    payment.markFailed(result.failReason());
                }
            }
            case EXPIRED -> {
                if (payment.getStatus() != PaymentStatus.DONE) {
                    payment.markExpired();
                }
            }
            case NOT_FOUND -> {
                // PG에 주문이 없다 = 위젯 단계까지 못 간 이탈. 승인 시도 흔적이 있어도 돈은 안 움직였다.
                if (payment.getStatus() == PaymentStatus.READY
                        || payment.getStatus() == PaymentStatus.IN_PROGRESS) {
                    payment.markExpired();
                }
            }
            // 아직 결론이 없는 상태 — 아무것도 확정하지 않는다(만료는 별도 정책이 처리).
            case READY, IN_PROGRESS, UNKNOWN -> { }
        }
        return toResponse(payment);
    }

    @Transactional(readOnly = true)
    public PaymentResponse toResponseOwned(Long userId, String orderId) {
        return toResponse(loadOwned(userId, orderId));
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> myPayments(Long userId) {
        List<Payment> payments = paymentRepository.findByUserIdOrderByCreatedAtDesc(userId);
        // 이용권을 결제 건별로 한 번에 당겨 N+1을 피한다.
        Map<Long, Entitlement> byPaymentId = entitlementRepository
                .findByPaymentIdIn(payments.stream().map(Payment::getId).toList()).stream()
                .collect(Collectors.toMap(Entitlement::getPaymentId, Function.identity()));
        return payments.stream()
                .map(payment -> PaymentResponse.of(payment, byPaymentId.get(payment.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<Entitlement> entitlementOf(Long paymentId) {
        return entitlementRepository.findByPaymentId(paymentId);
    }

    private PaymentResponse toResponse(Payment payment) {
        return PaymentResponse.of(payment,
                entitlementRepository.findByPaymentId(payment.getId()).orElse(null));
    }

    private void grantEntitlementOnce(Payment payment) {
        if (entitlementRepository.findByPaymentId(payment.getId()).isPresent()) {
            return;
        }
        // 행 락이 동시 지급을 이미 직렬화한다. payment_id 유니크는 최후의 안전판 —
        // 그래도 겹치면 이 트랜잭션이 통째로 실패하고, 다음 재동기화가 멱등하게 다시 지나간다.
        entitlementRepository.save(Entitlement.builder()
                .userId(payment.getUserId())
                .kind(payment.getItem().getKind())
                .totalCount(payment.getItem().getCount())
                .paymentId(payment.getId())
                .build());
        log.info("이용권 지급 paymentId={} userId={} {}x{}",
                payment.getId(), payment.getUserId(), payment.getItem().getKind(), payment.getItem().getCount());
    }

    private void revokeEntitlement(Payment payment) {
        entitlementRepository.findByPaymentId(payment.getId())
                .ifPresent(entitlement -> entitlementRepository.revoke(entitlement.getId(), LocalDateTime.now()));
    }
}
