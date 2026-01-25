package com.threeam.consent.service;

import com.threeam.consent.entity.ConsentType;
import com.threeam.consent.entity.UserConsent;
import com.threeam.consent.repository.UserConsentRepository;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConsentService {

    // 약관/방침을 개정하면 올린다 — "어떤 판에 동의했나"가 이력의 핵심이라 행마다 박아둔다.
    public static final String DOC_VERSION = "1.0";

    private final UserConsentRepository userConsentRepository;

    // 가입 진입 시점의 관문. 인증 코드 소비 같은 부수효과가 생기기 전에 불러서 통째로 거른다.
    public void requireSignupConsents(Set<String> consents) {
        if (!parse(consents).containsAll(ConsentType.SIGNUP_REQUIRED)) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
        }
    }

    // requireSignupConsents를 통과한 뒤에만 불린다 — 기록은 필수 세트로 고정(임의 타입 유입 차단).
    @Transactional
    public void recordSignupConsents(Long userId) {
        for (ConsentType type : ConsentType.SIGNUP_REQUIRED) {
            userConsentRepository.save(UserConsent.builder()
                    .userId(userId).type(type).docVersion(DOC_VERSION).build());
        }
    }

    @Transactional
    public void recordPurchaseConsent(Long userId, String orderId) {
        userConsentRepository.save(UserConsent.builder()
                .userId(userId).type(ConsentType.PURCHASE_POLICY)
                .docVersion(DOC_VERSION).orderId(orderId).build());
    }

    private Set<ConsentType> parse(Set<String> consents) {
        if (consents == null) {
            return Set.of();
        }
        try {
            return consents.stream().map(ConsentType::valueOf).collect(Collectors.toSet());
        } catch (IllegalArgumentException e) {
            // 모르는 값은 조작된 요청 — 누락과 같은 코드로 거절(내부 타입 목록을 응답으로 노출하지 않는다)
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
        }
    }
}
