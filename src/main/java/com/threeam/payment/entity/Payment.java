package com.threeam.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

// 결제 원장. 삭제, 덮어쓰기 없이 상태 전이만 쌓는다 — 환불 분쟁, 대사(정산)의 근거라
// 사연 같은 소프트 딜리트 대상과 달리 어떤 경우에도 행이 사라지면 안 된다.
@Entity
@Table(name = "payments", uniqueConstraints =
        @UniqueConstraint(name = "uk_payments_order_id", columnNames = "order_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // 우리가 발급하는 주문 식별자(UUID). PG와 우리 기록을 잇는 열쇠라 유니크.
    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    // PG가 발급하는 결제 식별자. 승인 시도 전에는 없다.
    @Column(name = "payment_key", length = 200)
    private String paymentKey;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 40)
    private PaymentItem item;

    // 주문 시점의 확정 금액. 상품 가격이 나중에 바뀌어도 이 값이 당시 기준이다.
    @Column(nullable = false)
    private int amount;

    // PG가 알려주는 결제수단(카드, 간편결제, 가상계좌 등). 승인 전에는 모른다.
    @Column(length = 30)
    private String method;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private PaymentStatus status;

    @Column(name = "fail_reason", length = 500)
    private String failReason;

    // 가상계좌(무통장) 입금 안내용. 가상계좌 결제일 때만 채워진다.
    @Column(name = "vbank_bank", length = 30)
    private String vbankBank;

    @Column(name = "vbank_account", length = 64)
    private String vbankAccount;

    @Column(name = "vbank_due_at")
    private LocalDateTime vbankDueAt;

    @Column(name = "cancel_reason", length = 200)
    private String cancelReason;

    // 실제 환불된 금액. 미사용분 비례 환불이라 amount보다 작을 수 있다.
    @Column(name = "canceled_amount", nullable = false)
    private int canceledAmount;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 재동기화가 "이 상태에 얼마나 머물렀나"를 판단하는 기준 시각.
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Payment(Long userId, String orderId, PaymentItem item) {
        this.userId = userId;
        this.orderId = orderId;
        this.item = item;
        this.amount = item.getAmount();
        this.status = PaymentStatus.READY;
    }

    public void markDone(String method, LocalDateTime approvedAt) {
        this.status = PaymentStatus.DONE;
        this.method = method;
        this.approvedAt = approvedAt;
        this.failReason = null;
    }

    public void markWaitingForDeposit(String method, String bank, String account, LocalDateTime dueAt) {
        this.status = PaymentStatus.WAITING_FOR_DEPOSIT;
        this.method = method;
        this.vbankBank = bank;
        this.vbankAccount = account;
        this.vbankDueAt = dueAt;
    }

    public void markFailed(String reason) {
        this.status = PaymentStatus.FAILED;
        this.failReason = reason;
    }

    public void markExpired() {
        this.status = PaymentStatus.EXPIRED;
    }

    public void markCanceled(int canceledAmount, LocalDateTime canceledAt) {
        this.status = PaymentStatus.CANCELED;
        this.canceledAmount = canceledAmount;
        this.canceledAt = canceledAt;
    }
}
