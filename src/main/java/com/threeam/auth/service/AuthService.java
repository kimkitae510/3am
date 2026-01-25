package com.threeam.auth.service;

import com.threeam.auth.dto.LoginRequest;
import com.threeam.auth.dto.OAuthLoginRequest;
import com.threeam.auth.dto.TokenResponse;
import com.threeam.auth.entity.RefreshToken;
import com.threeam.auth.oauth.OAuthClient;
import com.threeam.auth.oauth.OAuthProfile;
import com.threeam.auth.repository.RefreshTokenRepository;
import com.threeam.consent.service.ConsentService;
import com.threeam.global.config.JwtProperties;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.global.util.TokenHasher;
import com.threeam.security.jwt.JwtTokenProvider;
import com.threeam.security.jwt.TokenInvalidationRegistry;
import com.threeam.usage.WelcomeGiftService;
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
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final LoginAttemptGuard loginAttemptGuard;
    private final TokenInvalidationRegistry tokenInvalidationRegistry;
    private final OAuthClient oAuthClient;
    private final WelcomeGiftService welcomeGiftService;
    private final ConsentService consentService;

    @Transactional
    public TokenResponse login(LoginRequest request, String clientIp) {
        String email = request.getEmail();
        loginAttemptGuard.assertNotLocked(email, clientIp);

        // 이메일 존재 여부로 응답이 갈리면 계정 수집에 쓰인다. "없는 이메일"과 "틀린 비번"을 같은 실패로 합친다.
        // 탈퇴 계정도 조회 대상에서 빠지므로 같은 LOGIN_FAILED로 떨어진다.
        User user = userRepository.findByEmailAndDeletedAtIsNull(email).orElse(null);
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            loginAttemptGuard.recordFailure(email, clientIp);
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }

        loginAttemptGuard.recordSuccess(email, clientIp);
        return issueTokens(user);
    }

    // 소셜 로그인 겸 가입 — (provider, providerId)로 찾고 없으면 그 자리에서 만든다.
    // 로그인 잠금(LoginAttemptGuard)은 비밀번호 추측 방어라 소셜 경로엔 해당 없음.
    @Transactional
    public TokenResponse oauthLogin(AuthProvider provider, OAuthLoginRequest request) {
        OAuthProfile profile = oAuthClient.fetchProfile(
                provider, request.getCode(), request.getState(), request.getRedirectUri());

        User user = userRepository.findByProviderAndProviderId(provider, profile.providerId())
                .orElse(null);
        if (user == null) {
            user = registerSocialUser(profile, request.getConsents());
        } else if (user.isWithdrawn()) {
            // 이메일 가입과 같은 정책: 탈퇴 계정은 재사용 불가. 본인이 카카오/네이버 인증을
            // 마친 상태라 사유를 그대로 알려줘도 계정 열거 문제가 없다.
            throw new BusinessException(ErrorCode.OAUTH_WITHDRAWN_ACCOUNT);
        }
        return issueTokens(user);
    }

    private User registerSocialUser(OAuthProfile profile, java.util.Set<String> consents) {
        // 첫 로그인이 곧 가입 — 이메일 가입과 같은 필수 동의 세트를 계정 생성 전에 요구한다.
        consentService.requireSignupConsents(consents);
        // 소셜 이메일이 기존 계정(이메일 가입 또는 다른 소셜)과 겹치면 통합하지 않고 거부한다
        // (사용자 확정 정책 — 소셜 제공자의 이메일 검증 수준을 신뢰 조건에서 뺀다).
        if (profile.email() != null && userRepository.existsByEmail(profile.email())) {
            throw new BusinessException(ErrorCode.OAUTH_EMAIL_CONFLICT);
        }
        User saved = userRepository.save(User.builder()
                .email(profile.email())
                .role(Role.USER)
                .provider(profile.provider())
                .providerId(profile.providerId())
                .build());
        consentService.recordSignupConsents(saved.getId());
        // 이메일 가입과 동일한 가입 선물 — 첫 로그인이 곧 가입인 소셜 경로도 빠뜨리지 않는다.
        welcomeGiftService.grant(saved.getId());
        return saved;
    }

    @Transactional
    public TokenResponse reissue(String refreshToken) {
        // 서명, 만료뿐 아니라 타입도 검사 — access 토큰으로 재발급을 시도하는 경로를 막는다.
        if (!jwtTokenProvider.validateToken(refreshToken) || !jwtTokenProvider.isRefreshToken(refreshToken)) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        Long userId = jwtTokenProvider.getUserId(refreshToken);
        RefreshToken stored = refreshTokenRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TOKEN));

        // DB엔 해시만 저장한다 — 제시된 원문을 해시해 대조(DB 유출 시 토큰 재사용 차단).
        if (stored.isExpired() || !stored.matches(TokenHasher.sha256(refreshToken))) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TOKEN));

        return issueTokens(user);
    }

    @Transactional
    public void logout(Long userId) {
        refreshTokenRepository.deleteByUserId(userId);
        tokenInvalidationRegistry.invalidateAll(userId);
    }

    private TokenResponse issueTokens(User user) {
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getRole());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());
        saveOrRotate(user.getId(), refreshToken);
        return new TokenResponse(accessToken, refreshToken);
    }

    private void saveOrRotate(Long userId, String refreshToken) {
        LocalDateTime expiresAt = LocalDateTime.now()
                .plusSeconds(jwtProperties.getRefreshTokenValiditySeconds());
        // 원문이 아니라 해시를 저장한다. 원문은 유저만 갖고, 서버는 대조용 지문만 보관한다.
        String tokenHash = TokenHasher.sha256(refreshToken);

        refreshTokenRepository.findByUserId(userId)
                .ifPresentOrElse(
                        stored -> stored.rotate(tokenHash, expiresAt),
                        () -> refreshTokenRepository.save(RefreshToken.builder()
                                .userId(userId)
                                .token(tokenHash)
                                .expiresAt(expiresAt)
                                .build()));
    }
}
