package com.threeam.payment.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.threeam.payment.service.PaymentService;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// PG 웹훅 수신. 인증 없이 열리는 경로라 페이로드를 절대 믿지 않는다 —
// orderId만 꺼내 "이 주문을 다시 봐라"는 트리거로 쓰고, 실상태는 PG 조회 API로 재확인한다.
// 위조 웹훅이 할 수 있는 최대치는 불필요한 조회 한 번이지 상태 변경이 아니다.
@Slf4j
@RestController
@RequestMapping("/api/payments/webhook")
@RequiredArgsConstructor
public class PaymentWebhookController {

    private final PaymentService paymentService;

    @PostMapping("/toss")
    public CompletableFuture<ResponseEntity<Void>> toss(@RequestBody JsonNode payload) {
        // 상태 변경 이벤트는 data.orderId, 가상계좌 입금 콜백은 최상위 orderId에 실려 온다.
        String orderId = payload.path("data").path("orderId").asText(
                payload.path("orderId").asText(null));
        if (orderId == null || orderId.isBlank()) {
            log.warn("orderId 없는 웹훅 무시: eventType={}", payload.path("eventType").asText(""));
            return CompletableFuture.completedFuture(ResponseEntity.ok().build());
        }
        // 논블로킹 — 웹훅이 몰려도 서블릿 스레드를 PG 조회 시간만큼 잡아두지 않는다.
        // 실패하면 500으로 떨어져 토스가 재전송한다(재동기화 스케줄러도 뒤를 받친다).
        return paymentService.syncByOrderId(orderId)
                .thenApply(ignored -> ResponseEntity.ok().build());
    }
}
