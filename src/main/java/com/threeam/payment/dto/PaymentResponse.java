package com.threeam.payment.dto;

import com.threeam.payment.entity.Payment;
import com.threeam.payment.entity.PaymentStatus;
import com.threeam.usage.Entitlement;
import java.time.LocalDateTime;
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
    // 이용권 소진 현황과 환불 예상액. 이용권이 아직 없으면(승인 전, 실패) null.
    private final Integer totalCount;
    private final Integer usedCount;
    private final Integer refundableAmount;

    private PaymentResponse(Payment payment, Entitlement entitlement) {
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
        this.totalCount = entitlement == null ? null : entitlement.getTotalCount();
        this.usedCount = entitlement == null ? null : entitlement.getUsedCount();
        this.refundableAmount = entitlement == null || payment.getStatus() != PaymentStatus.DONE
                ? null
                : entitlement.refundableAmount(payment.getAmount());
    }

    public static PaymentResponse of(Payment payment, Entitlement entitlement) {
        return new PaymentResponse(payment, entitlement);
    }
}
