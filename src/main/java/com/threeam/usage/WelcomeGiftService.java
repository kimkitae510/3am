package com.threeam.usage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 결제 없이 주는 이용권의 유일한 창구. 가입 선물과 게스트 체험이 여기로 모인다 —
// 일일 무료 쿼터를 폐지하면서 "공짜로 주는 것"이 전부 이용권이 됐고, 그래서 나중에
// 이벤트나 재방문 선물을 붙일 때도 이 클래스에 메서드 하나를 더하면 된다.
@Service
@RequiredArgsConstructor
public class WelcomeGiftService {

    private final UsageProperties properties;
    private final EntitlementRepository entitlementRepository;

    // 가입 직후(이메일, 소셜 공통) 한 번. 게스트가 승격되는 경우에도 여기로 온다 —
    // 게스트 체험분이 남아 있으면 합산된다(같은 계정이라 어뷰징이 아니다).
    @Transactional
    public void grantSignupGift(Long userId) {
        grant(userId, UsageKind.CHAT, properties.getSignupGiftChat());
        grant(userId, UsageKind.ASSESSMENT, properties.getSignupGiftAssessment());
    }

    // 게스트 시작 시. 진단은 주지 않는다 — 계정 연결을 유도하는 지점이다.
    @Transactional
    public void grantGuestTrial(Long userId) {
        grant(userId, UsageKind.CHAT, properties.getGuestTrialChat());
    }

    private void grant(Long userId, UsageKind kind, int count) {
        if (count <= 0) {
            return;
        }
        entitlementRepository.save(Entitlement.builder()
                .userId(userId)
                .kind(kind)
                .totalCount(count)
                .build());
    }
}
