package com.threeam.auth.service;

import com.threeam.auth.dto.LoginRequest;
import com.threeam.auth.dto.TokenResponse;
import com.threeam.auth.entity.RefreshToken;
import com.threeam.auth.repository.RefreshTokenRepository;
import com.threeam.global.config.JwtProperties;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.security.jwt.JwtTokenProvider;
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

    @Transactional
    public TokenResponse login(LoginRequest request, String clientIp) {
        String email = request.getEmail();
        loginAttemptGuard.assertNotLocked(email, clientIp);

        // 이메일 존재 여부로 응답이 갈리면 계정 수집에 쓰인다. "없는 이메일"과 "틀린 비번"을 같은 실패로 합친다.
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            loginAttemptGuard.recordFailure(email, clientIp);
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }

        loginAttemptGuard.recordSuccess(email, clientIp);
        return issueTokens(user);
    }

    @Transactional
    public TokenResponse reissue(String refreshToken) {
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        Long userId = jwtTokenProvider.getUserId(refreshToken);
        RefreshToken stored = refreshTokenRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TOKEN));

        // DB에 저장된 현재 토큰과 일치하고, 만료되지 않았을 때만 재발급 (탈취, 구버전 차단)
        if (stored.isExpired() || !stored.matches(refreshToken)) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        return issueTokens(user);
    }

    @Transactional
    public void logout(Long userId) {
        refreshTokenRepository.deleteByUserId(userId);
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

        refreshTokenRepository.findByUserId(userId)
                .ifPresentOrElse(
                        stored -> stored.rotate(refreshToken, expiresAt),
                        () -> refreshTokenRepository.save(RefreshToken.builder()
                                .userId(userId)
                                .token(refreshToken)
                                .expiresAt(expiresAt)
                                .build()));
    }
}
