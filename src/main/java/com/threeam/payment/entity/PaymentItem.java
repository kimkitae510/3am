package com.threeam.payment.entity;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.usage.UsageKind;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 판매 상품 정의. 금액은 항상 서버의 이 정의가 기준이다 — 프론트가 보내는 금액은
// 검증 대상일 뿐 절대 가격 결정에 쓰지 않는다(위변조 차단).
// 가격을 바꿔도 지난 결제는 payments.amount에 당시 금액이 박제되어 있어 영향 없다.
@Getter
@RequiredArgsConstructor
public enum PaymentItem {

    ASSESSMENT_5("진단 5회권", UsageKind.ASSESSMENT, 5, 3900),
    ASSESSMENT_10("진단 10회권", UsageKind.ASSESSMENT, 10, 6900);

    private final String displayName;
    private final UsageKind kind;
    private final int count;
    private final int amount;

    public static PaymentItem parse(String code) {
        try {
            return valueOf(code);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(ErrorCode.PAYMENT_ITEM_NOT_FOUND);
        }
    }
}
