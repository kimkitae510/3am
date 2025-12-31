package com.threeam.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.mail.VerificationMailSender;
import com.threeam.user.entity.EmailVerification;
import com.threeam.user.repository.EmailVerificationRepository;
import com.threeam.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    @Mock
    private EmailVerificationRepository emailVerificationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private VerificationMailSender mailSender;

    @Mock
    private MailSendRateLimiter mailSendRateLimiter;

    @InjectMocks
    private EmailVerificationService emailVerificationService;

    @Test
    @DisplayName("발급 성공 - 숫자 6자리 코드를 해시로 저장하고 같은 코드를 메일로 보낸다")
    void issue_success() {
        given(userRepository.existsByEmail("a@a.com")).willReturn(false);
        given(emailVerificationRepository.findTopByEmailOrderByIdDesc("a@a.com"))
                .willReturn(Optional.empty());

        emailVerificationService.issue("a@a.com", "1.1.1.1");

        ArgumentCaptor<EmailVerification> saved = ArgumentCaptor.forClass(EmailVerification.class);
        verify(emailVerificationRepository).deleteByEmail("a@a.com");
        verify(emailVerificationRepository).save(saved.capture());

        ArgumentCaptor<String> sentCode = ArgumentCaptor.forClass(String.class);
        verify(mailSender).send(org.mockito.ArgumentMatchers.eq("a@a.com"), sentCode.capture());

        assertThat(sentCode.getValue()).matches("\\d{6}");
        assertThat(saved.getValue().getCodeHash()).isEqualTo(sha256(sentCode.getValue())); // 원문 미저장
        assertThat(saved.getValue().getExpiresAt()).isAfter(LocalDateTime.now());
    }

    @Test
    @DisplayName("발급 실패 - 이미 가입된 이메일이면 메일을 보내지 않는다")
    void issue_alreadyRegistered() {
        given(userRepository.existsByEmail("dup@a.com")).willReturn(true);

        assertThatThrownBy(() -> emailVerificationService.issue("dup@a.com", "1.1.1.1"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMAIL_ALREADY_EXISTS);
        verify(mailSender, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("발급 실패 - 60초 안에 재요청하면 쿨다운 예외, 새 코드를 만들지 않는다")
    void issue_cooldown() {
        given(userRepository.existsByEmail("a@a.com")).willReturn(false);
        EmailVerification prev = verification("a@a.com", "000000", LocalDateTime.now().plusMinutes(10));
        ReflectionTestUtils.setField(prev, "createdAt", LocalDateTime.now().minusSeconds(10));
        given(emailVerificationRepository.findTopByEmailOrderByIdDesc("a@a.com"))
                .willReturn(Optional.of(prev));

        assertThatThrownBy(() -> emailVerificationService.issue("a@a.com", "1.1.1.1"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VERIFICATION_RESEND_COOLDOWN);
        verify(emailVerificationRepository, never()).save(any());
        verify(mailSender, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("검증 성공 - 코드가 맞으면 해당 이메일의 코드를 삭제(소비)한다")
    void verify_success() {
        EmailVerification verification = verification("a@a.com", "123456", LocalDateTime.now().plusMinutes(5));
        given(emailVerificationRepository.findTopByEmailOrderByIdDesc("a@a.com"))
                .willReturn(Optional.of(verification));

        emailVerificationService.verifyAndConsume("a@a.com", "123456");

        verify(emailVerificationRepository).deleteByEmail("a@a.com");
    }

    @Test
    @DisplayName("검증 실패 - 발급 이력이 없으면 CODE_INVALID (이력 유무를 응답으로 구분하지 않음)")
    void verify_noIssueHistory() {
        given(emailVerificationRepository.findTopByEmailOrderByIdDesc("a@a.com"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> emailVerificationService.verifyAndConsume("a@a.com", "123456"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VERIFICATION_CODE_INVALID);
    }

    @Test
    @DisplayName("검증 실패 - 만료된 코드는 CODE_EXPIRED")
    void verify_expired() {
        EmailVerification verification = verification("a@a.com", "123456", LocalDateTime.now().minusMinutes(1));
        given(emailVerificationRepository.findTopByEmailOrderByIdDesc("a@a.com"))
                .willReturn(Optional.of(verification));

        assertThatThrownBy(() -> emailVerificationService.verifyAndConsume("a@a.com", "123456"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VERIFICATION_CODE_EXPIRED);
    }

    @Test
    @DisplayName("검증 실패 - 코드가 틀리면 시도 횟수를 늘리고 CODE_INVALID")
    void verify_wrongCode() {
        EmailVerification verification = verification("a@a.com", "123456", LocalDateTime.now().plusMinutes(5));
        given(emailVerificationRepository.findTopByEmailOrderByIdDesc("a@a.com"))
                .willReturn(Optional.of(verification));

        assertThatThrownBy(() -> emailVerificationService.verifyAndConsume("a@a.com", "999999"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VERIFICATION_CODE_INVALID);
        assertThat(verification.getAttemptCount()).isEqualTo(1);
        verify(emailVerificationRepository, never()).deleteByEmail(anyString());
    }

    @Test
    @DisplayName("검증 실패 - 5회 틀린 뒤에는 맞는 코드여도 ATTEMPTS_EXCEEDED")
    void verify_attemptsExceeded() {
        EmailVerification verification = verification("a@a.com", "123456", LocalDateTime.now().plusMinutes(5));
        ReflectionTestUtils.setField(verification, "attemptCount", 5);
        given(emailVerificationRepository.findTopByEmailOrderByIdDesc("a@a.com"))
                .willReturn(Optional.of(verification));

        assertThatThrownBy(() -> emailVerificationService.verifyAndConsume("a@a.com", "123456"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED);
    }

    private EmailVerification verification(String email, String code, LocalDateTime expiresAt) {
        return EmailVerification.builder()
                .email(email)
                .codeHash(sha256(code))
                .expiresAt(expiresAt)
                .build();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
