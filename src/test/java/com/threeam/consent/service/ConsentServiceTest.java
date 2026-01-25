package com.threeam.consent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.threeam.consent.entity.ConsentType;
import com.threeam.consent.entity.UserConsent;
import com.threeam.consent.repository.UserConsentRepository;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsentServiceTest {

    @Mock
    private UserConsentRepository userConsentRepository;

    @InjectMocks
    private ConsentService consentService;

    @Test
    @DisplayName("가입 동의 검증 - 필수 세트가 전부 있으면 통과한다")
    void requireSignupConsents_full() {
        assertThatCode(() -> consentService.requireSignupConsents(
                Set.of("TERMS", "PRIVACY", "SENSITIVE", "DISCLAIMER")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("가입 동의 검증 - 하나라도 빠지면 CONSENT_REQUIRED")
    void requireSignupConsents_missingOne() {
        assertThatThrownBy(() -> consentService.requireSignupConsents(
                Set.of("TERMS", "PRIVACY", "DISCLAIMER")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONSENT_REQUIRED);
    }

    @Test
    @DisplayName("가입 동의 검증 - null이면 CONSENT_REQUIRED")
    void requireSignupConsents_null() {
        assertThatThrownBy(() -> consentService.requireSignupConsents(null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONSENT_REQUIRED);
    }

    @Test
    @DisplayName("가입 동의 검증 - 모르는 타입 문자열은 500이 아니라 CONSENT_REQUIRED")
    void requireSignupConsents_unknownType() {
        assertThatThrownBy(() -> consentService.requireSignupConsents(
                Set.of("TERMS", "PRIVACY", "SENSITIVE", "DISCLAIMER", "HACK")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONSENT_REQUIRED);
    }

    @Test
    @DisplayName("가입 동의 기록 - 필수 세트 4건을 문서 버전과 함께 저장한다")
    void recordSignupConsents() {
        consentService.recordSignupConsents(1L);

        ArgumentCaptor<UserConsent> captor = ArgumentCaptor.forClass(UserConsent.class);
        verify(userConsentRepository, times(4)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(UserConsent::getType)
                .containsExactlyInAnyOrderElementsOf(ConsentType.SIGNUP_REQUIRED);
        assertThat(captor.getAllValues())
                .allSatisfy(consent -> {
                    assertThat(consent.getUserId()).isEqualTo(1L);
                    assertThat(consent.getDocVersion()).isEqualTo(ConsentService.DOC_VERSION);
                    assertThat(consent.getOrderId()).isNull();
                });
    }

    @Test
    @DisplayName("결제 고지 동의 기록 - 주문 번호와 묶어 저장한다")
    void recordPurchaseConsent() {
        consentService.recordPurchaseConsent(1L, "order-1");

        ArgumentCaptor<UserConsent> captor = ArgumentCaptor.forClass(UserConsent.class);
        verify(userConsentRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(ConsentType.PURCHASE_POLICY);
        assertThat(captor.getValue().getOrderId()).isEqualTo("order-1");
    }
}
