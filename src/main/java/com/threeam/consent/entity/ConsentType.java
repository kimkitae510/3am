package com.threeam.consent.entity;

import java.util.Set;

public enum ConsentType {
    TERMS,          // 이용약관
    PRIVACY,        // 개인정보 수집, 이용
    SENSITIVE,      // 민감정보(이별, 연애 이야기) 수집, 이용 — 개인정보보호법상 별도 동의 대상
    DISCLAIMER,     // AI 참고 정보 면책 고지 확인
    PURCHASE_POLICY; // 결제 시 청약철회 제한 고지 — 전자상거래법 제17조 요건, 주문 단위로 기록

    // 가입이 성립하려면 전부 있어야 하는 세트. 하나라도 빠지면 가입 자체를 거른다.
    public static final Set<ConsentType> SIGNUP_REQUIRED =
            Set.of(TERMS, PRIVACY, SENSITIVE, DISCLAIMER);
}
