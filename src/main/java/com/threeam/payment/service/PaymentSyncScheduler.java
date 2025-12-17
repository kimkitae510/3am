package com.threeam.payment.service;

import com.threeam.payment.client.PaymentProperties;
import com.threeam.payment.entity.Payment;
import com.threeam.payment.entity.PaymentStatus;
import com.threeam.payment.repository.PaymentRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 결제 상태 머신의 안전망. 승인/취소 응답이 유실돼 불명 상태에 갇힌 결제를
// PG 조회로 실상태 확정한다 — "돈은 나갔는데 이용권이 없다"를 스스로 복구하는 장치.
// 웹훅이 정상 동작하면 대부분 여기까지 오기 전에 끝나지만, 웹훅은 로컬 개발에선 수신이
// 안 되고 운영에서도 유실될 수 있어 이 폴링이 최종 보증을 맡는다.
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentSyncScheduler {

    private final PaymentRepository paymentRepository;
    private final PaymentTxService txService;
    private final PaymentService paymentService;
    private final PaymentProperties properties;

    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void reconcile() {
        LocalDateTime now = LocalDateTime.now();
        expireAbandonedOrders(now);
        LocalDateTime staleBefore = now.minusMinutes(properties.getSyncAfterMinutes());
        syncEach(paymentRepository.findTop20ByStatusAndUpdatedAtBefore(PaymentStatus.IN_PROGRESS, staleBefore));
        syncEach(paymentRepository.findTop20ByStatusAndUpdatedAtBefore(PaymentStatus.CANCEL_REQUESTED, staleBefore));
        // 입금 기한이 지난 가상계좌: 입금됐는데 웹훅이 유실됐을 수도, 그냥 만료일 수도 — 조회로 확정.
        syncEach(paymentRepository.findTop20ByStatusAndVbankDueAtBefore(PaymentStatus.WAITING_FOR_DEPOSIT, now));
    }

    // 주문만 만들고 위젯에서 이탈한 건. PG에 승인 요청을 한 적이 없으니 조회 없이 만료 처리한다.
    private void expireAbandonedOrders(LocalDateTime now) {
        List<Payment> abandoned = paymentRepository.findTop20ByStatusAndCreatedAtBefore(
                PaymentStatus.READY, now.minusMinutes(properties.getOrderExpireMinutes()));
        for (Payment payment : abandoned) {
            if (txService.expire(payment.getOrderId())) {
                log.info("방치 주문 만료 orderId={}", payment.getOrderId());
            }
        }
    }

    private void syncEach(List<Payment> payments) {
        for (Payment payment : payments) {
            try {
                paymentService.syncByOrderId(payment.getOrderId()).join();
                log.info("결제 재동기화 orderId={} 이전상태={}", payment.getOrderId(), payment.getStatus());
            } catch (RuntimeException e) {
                // 한 건의 실패가 나머지 동기화를 막지 않게 한다. 다음 주기에 다시 시도된다.
                log.error("결제 재동기화 실패 orderId={}", payment.getOrderId(), e);
            }
        }
    }
}
