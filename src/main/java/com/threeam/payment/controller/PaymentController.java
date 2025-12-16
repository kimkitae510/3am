package com.threeam.payment.controller;

import com.threeam.payment.dto.CancelRequest;
import com.threeam.payment.dto.ConfirmRequest;
import com.threeam.payment.dto.OrderCreateRequest;
import com.threeam.payment.dto.OrderCreateResponse;
import com.threeam.payment.dto.PaymentConfigResponse;
import com.threeam.payment.dto.PaymentResponse;
import com.threeam.payment.service.PaymentService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    // 결제 화면 진입용: 상품 목록 + 위젯 clientKey(공개 키). mock이면 clientKey가 비어 있다.
    @GetMapping("/config")
    public ResponseEntity<PaymentConfigResponse> config() {
        return ResponseEntity.ok(paymentService.config());
    }

    @PostMapping("/orders")
    public ResponseEntity<OrderCreateResponse> createOrder(@AuthenticationPrincipal Long userId,
                                                           @Valid @RequestBody OrderCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(paymentService.createOrder(userId, request));
    }

    // 위젯 successUrl에서 넘어온 결제를 서버가 최종 승인한다. PG 호출이 끼므로 논블로킹.
    @PostMapping("/confirm")
    public CompletableFuture<ResponseEntity<PaymentResponse>> confirm(@AuthenticationPrincipal Long userId,
                                                                      @Valid @RequestBody ConfirmRequest request) {
        return paymentService.confirm(userId, request).thenApply(ResponseEntity::ok);
    }

    @GetMapping
    public ResponseEntity<List<PaymentResponse>> myPayments(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(paymentService.myPayments(userId));
    }

    // 단건 조회 — 가상계좌 입금 대기, 결과 확인 지연(P007) 화면의 폴링 용도.
    @GetMapping("/{orderId}")
    public ResponseEntity<PaymentResponse> myPayment(@AuthenticationPrincipal Long userId,
                                                     @PathVariable String orderId) {
        return ResponseEntity.ok(paymentService.myPayment(userId, orderId));
    }

    @PostMapping("/{orderId}/cancel")
    public CompletableFuture<ResponseEntity<PaymentResponse>> cancel(@AuthenticationPrincipal Long userId,
                                                                     @PathVariable String orderId,
                                                                     @Valid @RequestBody CancelRequest request) {
        return paymentService.cancel(userId, orderId, request).thenApply(ResponseEntity::ok);
    }
}
