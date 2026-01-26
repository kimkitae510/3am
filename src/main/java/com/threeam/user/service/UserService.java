package com.threeam.user.service;

import com.threeam.auth.repository.RefreshTokenRepository;
import com.threeam.consent.service.ConsentService;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.security.jwt.TokenInvalidationRegistry;
import com.threeam.usage.WelcomeGiftService;
import com.threeam.user.dto.PasswordChangeRequest;
import com.threeam.user.dto.SignupRequest;
import com.threeam.user.dto.SignupResponse;
import com.threeam.user.dto.UserMeResponse;
import com.threeam.user.entity.AuthProvider;
import com.threeam.user.entity.Role;
import com.threeam.user.entity.User;
import com.threeam.user.repository.UserRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SignupRateLimiter signupRateLimiter;
    private final EmailVerificationService emailVerificationService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenInvalidationRegistry tokenInvalidationRegistry;
    private final WelcomeGiftService welcomeGiftService;
    private final ConsentService consentService;

    @Transactional
    public SignupResponse signup(SignupRequest request, String clientIp) {
        signupRateLimiter.check(clientIp);
        // 인증 코드가 소비되기 전에 거른다 — 동의 누락으로 실패했는데 코드만 날아가는 상황 방지.
        consentService.requireSignupConsents(request.getConsents());

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        // 코드 검증은 별도 트랜잭션(REQUIRES_NEW)에서 소비된다. 이후 save가 실패하는 극단 케이스엔
        // 코드를 다시 요청해야 하지만, 그 대가로 실패 시도 카운트가 롤백에 휩쓸리지 않는다.
        emailVerificationService.verifyAndConsume(request.getEmail(), request.getVerificationCode());

        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(Role.USER)
                .provider(AuthProvider.EMAIL)
                .build();

        User saved = userRepository.save(user);
        consentService.recordSignupConsents(saved.getId());
        welcomeGiftService.grant(saved.getId());
        return SignupResponse.from(saved);
    }

    public UserMeResponse me(Long userId) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        return UserMeResponse.from(user);
    }

    @Transactional
    public void changePassword(Long userId, PasswordChangeRequest request) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (!user.hasPassword()) {
            throw new BusinessException(ErrorCode.SOCIAL_ACCOUNT_NO_PASSWORD);
        }
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }

        user.changePassword(passwordEncoder.encode(request.getNewPassword()));
        // 비밀번호가 바뀌면 기존 세션(리프레시 + 이미 발급된 access)을 모두 끊어 재로그인을 강제한다.
        refreshTokenRepository.deleteByUserId(userId);
        tokenInvalidationRegistry.invalidateAll(userId);
    }

    @Transactional
    public void withdraw(Long userId) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        // 소프트 딜리트: 기록은 남기되 로그인/조회 대상에서 빠진다. 이메일은 재사용 차단을 위해 그대로 둔다.
        user.withdraw(LocalDateTime.now());
        refreshTokenRepository.deleteByUserId(userId);
        tokenInvalidationRegistry.invalidateAll(userId);
    }
}
