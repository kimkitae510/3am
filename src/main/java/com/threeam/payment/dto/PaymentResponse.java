package com.threeam.payment.dto;

import com.threeam.payment.entity.Payment;
import com.threeam.payment.entity.PaymentStatus;
import com.threeam.usage.Entitlement;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;

@Getter
public class PaymentResponse {

    private final String orderId;
    private final String item;
    private final String itemName;
    private final int amount;
    private final PaymentStatus status;
    private final String method;
    private final String failReason;
    private final String vbankBank;
    private final String vbankAccount;
    private final LocalDateTime vbankDueAt;
    private final int canceledAmount;
    private final LocalDateTime createdAt;
    private final LocalDateTime approvedAt;
    private final LocalDateTime canceledAt;
    // 이 결제가 지급한 이용권들의 소진 현황(묶음이면 여러 개). 승인 전, 실패면 빈 목록.
    private final List<EntitlementView> entitlements;
    // 지금 환불하면 받을 금액(남은 횟수 x 회당 가치). DONE이 아닐 땐 null.
    private final Integer refundableAmount;

    private PaymentResponse(Payment payment, List<Entitlement> entitlements) {
        this.orderId = payment.getOrderId();
        this.item = payment.getItem().name();
        this.itemName = payment.getItem().getDisplayName();
        this.amount = payment.getAmount();
        this.status = payment.getStatus();
        this.method = payment.getMethod();
        this.failReason = payment.getFailReason();
        this.vbankBank = payment.getVbankBank();
        this.vbankAccount = payment.getVbankAccount();
        this.vbankDueAt = payment.getVbankDueAt();
        this.canceledAmount = payment.getCanceledAmount();
        this.createdAt = payment.getCreatedAt();
        this.approvedAt = payment.getApprovedAt();
        this.canceledAt = payment.getCanceledAt();
        this.entitlements = entitlements.stream().map(EntitlementView::new).toList();
        this.refundableAmount = payment.getStatus() == PaymentStatus.DONE && !entitlements.isEmpty()
                ? payment.getItem().refundableAmount(entitlements)
                : null;
    }

    public static PaymentResponse of(Payment payment, List<Entitlement> entitlements) {
        return new PaymentResponse(payment, entitlements);
    }

    @Getter
    public static class EntitlementView {
        private final String kind;
        private final int totalCount;
        private final int usedCount;
        private final int remainingCount;

        private EntitlementView(Entitlement entitlement) {
            this.kind = entitlement.getKind().name();
            this.totalCount = entitlement.getTotalCount();
            this.usedCount = entitlement.getUsedCount();
            this.remainingCount = entitlement.remainingCount();
        }
    }
}
