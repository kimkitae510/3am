package com.threeam.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.user.dto.SignupRequest;
import com.threeam.user.dto.SignupResponse;
import com.threeam.user.entity.Role;
import com.threeam.user.entity.User;
import com.threeam.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private SignupRateLimiter signupRateLimiter;

    @Mock
    private EmailVerificationService emailVerificationService;

    @Mock
    private com.threeam.auth.repository.RefreshTokenRepository refreshTokenRepository;

    @Mock
    private com.threeam.security.jwt.TokenInvalidationRegistry tokenInvalidationRegistry;

    @Mock
    private com.threeam.usage.WelcomeGiftService welcomeGiftService;

    @Mock
    private com.threeam.consent.service.ConsentService consentService;

    @InjectMocks
    private UserService userService;

    @Test
    @DisplayName("회원가입 성공 - 비밀번호를 암호화해 저장하고 응답을 반환한다")
    void signup_success() {
        SignupRequest request = signupRequest("a@a.com", "password123");
        given(userRepository.existsByEmail("a@a.com")).willReturn(false);
        given(passwordEncoder.encode("password123")).willReturn("encodedPw");
        given(userRepository.save(any(User.class))).willAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 1L);
            return saved;
        });

        SignupResponse response = userService.signup(request, "1.1.1.1");

        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getEmail()).isEqualTo("a@a.com");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPassword()).isEqualTo("encodedPw"); // 평문 저장 안 함
        assertThat(captor.getValue().getRole()).isEqualTo(Role.USER);
        verify(emailVerificationService).verifyAndConsume("a@a.com", "123456"); // 인증 코드 검증을 거친다
        verify(consentService).recordSignupConsents(1L); // 동의 이력을 남긴다
        verify(welcomeGiftService).grant(1L); // 가입 선물 이용권 지급
    }

    @Test
    @DisplayName("회원가입 실패 - 필수 동의 누락이면 인증 코드가 소비되기 전에 거른다")
    void signup_consentMissing() {
        SignupRequest request = signupRequest("a@a.com", "password123");
        org.mockito.BDDMockito.willThrow(new BusinessException(ErrorCode.CONSENT_REQUIRED))
                .given(consentService).requireSignupConsents(any());

        assertThatThrownBy(() -> userService.signup(request, "1.1.1.1"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONSENT_REQUIRED);
        verify(emailVerificationService, org.mockito.Mockito.never())
                .verifyAndConsume(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(userRepository, org.mockito.Mockito.never()).save(any(User.class));
    }

    @Test
    @DisplayName("회원가입 실패 - 인증 코드 검증에 실패하면 저장하지 않는다")
    void signup_verificationFailed() {
        SignupRequest request = signupRequest("a@a.com", "password123");
        given(userRepository.existsByEmail("a@a.com")).willReturn(false);
        org.mockito.BDDMockito.willThrow(new BusinessException(ErrorCode.VERIFICATION_CODE_INVALID))
                .given(emailVerificationService).verifyAndConsume("a@a.com", "123456");

        assertThatThrownBy(() -> userService.signup(request, "1.1.1.1"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VERIFICATION_CODE_INVALID);
        verify(userRepository, org.mockito.Mockito.never()).save(any(User.class));
    }

    @Test
    @DisplayName("회원가입 실패 - 이미 존재하는 이메일이면 EMAIL_ALREADY_EXISTS 예외")
    void signup_duplicateEmail() {
        SignupRequest request = signupRequest("dup@a.com", "password123");
        given(userRepository.existsByEmail("dup@a.com")).willReturn(true);

        assertThatThrownBy(() -> userService.signup(request, "1.1.1.1"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMAIL_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("회원가입 실패 - IP 가입 한도를 넘으면 SIGNUP_RATE_LIMITED 예외")
    void signup_rateLimited() {
        SignupRequest request = signupRequest("a@a.com", "password123");
        org.mockito.BDDMockito.willThrow(new BusinessException(ErrorCode.SIGNUP_RATE_LIMITED))
                .given(signupRateLimiter).check("9.9.9.9");

        assertThatThrownBy(() -> userService.signup(request, "9.9.9.9"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SIGNUP_RATE_LIMITED);
    }

    @Test
    @DisplayName("내 정보 조회 - 회원번호, 이메일, 가입 방법을 돌려준다")
    void me_success() {
        User user = userWithId(7L, "encodedPw");
        given(userRepository.findByIdAndDeletedAtIsNull(7L)).willReturn(java.util.Optional.of(user));

        com.threeam.user.dto.UserMeResponse response = userService.me(7L);

        assertThat(response.getId()).isEqualTo(7L);
        assertThat(response.getEmail()).isEqualTo("a@a.com");
        assertThat(response.getProvider()).isEqualTo("EMAIL");
    }

    @Test
    @DisplayName("내 정보 조회 - 탈퇴/미존재 계정이면 USER_NOT_FOUND")
    void me_notFound() {
        given(userRepository.findByIdAndDeletedAtIsNull(9L)).willReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> userService.me(9L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("비밀번호 변경 성공 - 현재 비번 확인 후 교체하고 세션을 모두 끊는다")
    void changePassword_success() {
        User user = userWithId(1L, "encodedOld");
        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(java.util.Optional.of(user));
        given(passwordEncoder.matches("oldPw", "encodedOld")).willReturn(true);
        given(passwordEncoder.encode("newPw12345")).willReturn("encodedNew");

        userService.changePassword(1L, passwordChangeRequest("oldPw", "newPw12345"));

        assertThat(user.getPassword()).isEqualTo("encodedNew");
        verify(refreshTokenRepository).deleteByUserId(1L);
        verify(tokenInvalidationRegistry).invalidateAll(1L);
    }

    @Test
    @DisplayName("비밀번호 변경 실패 - 현재 비번이 틀리면 INVALID_PASSWORD, 교체 안 함")
    void changePassword_wrongCurrent() {
        User user = userWithId(1L, "encodedOld");
        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(java.util.Optional.of(user));
        given(passwordEncoder.matches("wrong", "encodedOld")).willReturn(false);

        assertThatThrownBy(() -> userService.changePassword(1L, passwordChangeRequest("wrong", "newPw12345")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PASSWORD);
        assertThat(user.getPassword()).isEqualTo("encodedOld");
    }

    @Test
    @DisplayName("비밀번호 변경 실패 - 소셜 계정(비밀번호 없음)은 명시적으로 거부한다")
    void changePassword_socialAccount() {
        User user = User.builder()
                .role(Role.USER)
                .provider(com.threeam.user.entity.AuthProvider.KAKAO).providerId("kakao-1")
                .build();
        ReflectionTestUtils.setField(user, "id", 1L);
        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(java.util.Optional.of(user));

        assertThatThrownBy(() -> userService.changePassword(1L, passwordChangeRequest("any", "newPw12345")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SOCIAL_ACCOUNT_NO_PASSWORD);
    }

    @Test
    @DisplayName("탈퇴 - 소프트 딜리트하고 세션을 모두 끊는다")
    void withdraw_success() {
        User user = userWithId(1L, "encodedPw");
        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(java.util.Optional.of(user));

        userService.withdraw(1L);

        assertThat(user.isWithdrawn()).isTrue();
        verify(refreshTokenRepository).deleteByUserId(1L);
        verify(tokenInvalidationRegistry).invalidateAll(1L);
    }

    @Test
    @DisplayName("게스트 승격 - 새 계정 없이 게스트 행을 이메일 계정으로 교체하고, 동의와 가입 선물을 처리한다")
    void linkGuestEmail_success() {
        User guest = User.builder()
                .role(Role.USER)
                .provider(com.threeam.user.entity.AuthProvider.GUEST)
                .providerId("guest-uuid")
                .build();
        ReflectionTestUtils.setField(guest, "id", 9L);
        SignupRequest request = signupRequest("a@a.com", "password123");
        given(userRepository.findByIdAndDeletedAtIsNull(9L)).willReturn(java.util.Optional.of(guest));
        given(userRepository.existsByEmail("a@a.com")).willReturn(false);
        given(passwordEncoder.encode("password123")).willReturn("encodedPw");

        userService.linkGuestEmail(9L, request);

        verify(userRepository, org.mockito.Mockito.never()).save(any(User.class)); // 행 교체지 신규 생성이 아니다
        assertThat(guest.getProvider()).isEqualTo(com.threeam.user.entity.AuthProvider.EMAIL);
        assertThat(guest.getEmail()).isEqualTo("a@a.com");
        assertThat(guest.getPassword()).isEqualTo("encodedPw");
        assertThat(guest.getProviderId()).isNull();
        verify(emailVerificationService).verifyAndConsume("a@a.com", "123456"); // 가입과 같은 관문
        verify(consentService).recordSignupConsents(9L);
        verify(welcomeGiftService).grant(9L); // 승격이 실질적 가입 — 선물은 이때
    }

    @Test
    @DisplayName("게스트 승격 실패 - 게스트가 아닌 계정의 요청은 거부한다")
    void linkGuestEmail_notGuest() {
        User member = userWithId(1L, "encodedPw");
        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(java.util.Optional.of(member));

        assertThatThrownBy(() -> userService.linkGuestEmail(1L, signupRequest("a@a.com", "password123")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);
        verify(welcomeGiftService, org.mockito.Mockito.never()).grant(any());
    }

    private User userWithId(Long id, String password) {
        User user = User.builder()
                .email("a@a.com").password(password).role(Role.USER).build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private com.threeam.user.dto.PasswordChangeRequest passwordChangeRequest(String current, String next) {
        com.threeam.user.dto.PasswordChangeRequest request = new com.threeam.user.dto.PasswordChangeRequest();
        ReflectionTestUtils.setField(request, "currentPassword", current);
        ReflectionTestUtils.setField(request, "newPassword", next);
        return request;
    }

    private SignupRequest signupRequest(String email, String password) {
        SignupRequest request = new SignupRequest();
        ReflectionTestUtils.setField(request, "email", email);
        ReflectionTestUtils.setField(request, "password", password);
        ReflectionTestUtils.setField(request, "verificationCode", "123456");
        ReflectionTestUtils.setField(request, "consents",
                java.util.Set.of("TERMS", "PRIVACY", "SENSITIVE", "DISCLAIMER"));
        return request;
    }
}
