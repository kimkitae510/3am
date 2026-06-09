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

    // 게스트를 거치지 않고 바로 가입한 경우.
    @Transactional
    public void grantSignupGift(Long userId) {
        grant(userId, UsageKind.CHAT, properties.getSignupGiftChat());
        grant(userId, UsageKind.ASSESSMENT, properties.getSignupGiftAssessment());
    }

    // 게스트가 계정을 연결한 경우. 대화분이 가입 선물보다 적은 건 체험분을 이미 받았기 때문이다 —
    // 같은 값을 주면 게스트를 거친 사람이 바로 가입한 사람보다 총량에서 앞선다.
    // 진단은 체험에 없었으므로 가입과 같은 수를 준다.
    @Transactional
    public void grantGuestUpgradeGift(Long userId) {
        grant(userId, UsageKind.CHAT, properties.getGuestUpgradeGiftChat());
        grant(userId, UsageKind.ASSESSMENT, properties.getSignupGiftAssessment());
    }

    // 게스트 시작 시. 진단은 주지 않는다 — 계정 연결을 유도하는 지점이다.
    @Transactional
    public void grantGuestTrial(Long userId) {
        grant(userId, UsageKind.CHAT, properties.getGuestTrialChat());
    }

    // 진단 평가 보상. 진단당 평가 1회 제약(ReviewService)이 지급 횟수의 상한을 겸한다.
    @Transactional
    public void grantReviewGift(Long userId) {
        grant(userId, UsageKind.CHAT, properties.getReviewGiftChat());
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
