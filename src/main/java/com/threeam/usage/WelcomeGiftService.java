package com.threeam.usage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 가입 선물 이용권(대화 5회 + 진단 1회). "가입 당일만 한도 상향" 방식은 그날 못 쓰면
// 증발해 유저에게 불리해서, 이월되는 이용권으로 지급한다(사용자 결정).
// 차감 순서는 결제 이용권과 동일: 일일 무료 한도를 먼저 쓰고 이용권에서 꺼낸다.
@Service
@RequiredArgsConstructor
public class WelcomeGiftService {

    public static final int CHAT_COUNT = 5;
    public static final int ASSESSMENT_COUNT = 1;

    private final EntitlementRepository entitlementRepository;

    // 가입 직후(이메일/소셜 공통) 한 번 호출된다. 결제가 없으므로 paymentId는 null.
    @Transactional
    public void grant(Long userId) {
        entitlementRepository.save(Entitlement.builder()
                .userId(userId)
                .kind(UsageKind.CHAT)
                .totalCount(CHAT_COUNT)
                .build());
        entitlementRepository.save(Entitlement.builder()
                .userId(userId)
                .kind(UsageKind.ASSESSMENT)
                .totalCount(ASSESSMENT_COUNT)
                .build());
    }
}
