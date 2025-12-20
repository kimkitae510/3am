package com.threeam.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.threeam.auth.dto.LoginRequest;
import com.threeam.auth.dto.TokenResponse;
import com.threeam.auth.entity.RefreshToken;
import com.threeam.auth.repository.RefreshTokenRepository;
import com.threeam.global.config.JwtProperties;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.security.jwt.JwtTokenProvider;
import com.threeam.user.entity.Role;
import com.threeam.user.entity.User;
import com.threeam.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private JwtProperties jwtProperties;

    @Mock
    private LoginAttemptGuard loginAttemptGuard;

    @InjectMocks
    private AuthService authService;

    @Test
    @DisplayName("로그인 성공 - 토큰을 발급하고 RefreshToken을 저장한다")
    void login_success() {
        User user = userWithId(1L, "a@a.com", "encodedPw");
        given(userRepository.findByEmail("a@a.com")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("rawPw", "encodedPw")).willReturn(true);
        given(jwtTokenProvider.generateAccessToken(1L, Role.USER)).willReturn("access");
        given(jwtTokenProvider.generateRefreshToken(1L)).willReturn("refresh");
        given(jwtProperties.getRefreshTokenValiditySeconds()).willReturn(1209600L);
        given(refreshTokenRepository.findByUserId(1L)).willReturn(Optional.empty());

        TokenResponse response = authService.login(loginRequest("a@a.com", "rawPw"), "1.1.1.1");

        assertThat(response.getAccessToken()).isEqualTo("access");
        assertThat(response.getRefreshToken()).isEqualTo("refresh");
        verify(refreshTokenRepository).save(any(RefreshToken.class));
        verify(loginAttemptGuard).recordSuccess("a@a.com", "1.1.1.1");
    }

    @Test
    @DisplayName("로그인 실패 - 존재하지 않는 이메일도 LOGIN_FAILED로 통일(계정 존재 여부 비노출) + 실패 기록")
    void login_userNotFound() {
        given(userRepository.findByEmail("x@a.com")).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(loginRequest("x@a.com", "rawPw"), "1.1.1.1"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.LOGIN_FAILED);
        verify(loginAttemptGuard).recordFailure("x@a.com", "1.1.1.1");
    }

    @Test
    @DisplayName("로그인 실패 - 비밀번호가 틀리면 동일한 LOGIN_FAILED + 실패 기록")
    void login_invalidPassword() {
        User user = userWithId(1L, "a@a.com", "encodedPw");
        given(userRepository.findByEmail("a@a.com")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("wrong", "encodedPw")).willReturn(false);

        assertThatThrownBy(() -> authService.login(loginRequest("a@a.com", "wrong"), "1.1.1.1"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.LOGIN_FAILED);
        verify(loginAttemptGuard).recordFailure("a@a.com", "1.1.1.1");
    }

    @Test
    @DisplayName("로그인 실패 - 잠금 상태면 검증 전에 LOGIN_LOCKED")
    void login_locked() {
        org.mockito.BDDMockito.willThrow(new BusinessException(ErrorCode.LOGIN_LOCKED))
                .given(loginAttemptGuard).assertNotLocked("a@a.com", "1.1.1.1");

        assertThatThrownBy(() -> authService.login(loginRequest("a@a.com", "rawPw"), "1.1.1.1"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.LOGIN_LOCKED);
    }

    @Test
    @DisplayName("재발급 성공 - DB의 RefreshToken과 일치하면 새 토큰 발급 후 회전한다")
    void reissue_success() {
        String oldRefresh = "refresh";
        RefreshToken stored = RefreshToken.builder()
                .userId(1L).token(oldRefresh).expiresAt(LocalDateTime.now().plusDays(1)).build();
        User user = userWithId(1L, "a@a.com", "encodedPw");
        given(jwtTokenProvider.validateToken(oldRefresh)).willReturn(true);
        given(jwtTokenProvider.getUserId(oldRefresh)).willReturn(1L);
        given(refreshTokenRepository.findByUserId(1L)).willReturn(Optional.of(stored));
        given(userRepository.findById(1L)).willReturn(Optional.of(user));
        given(jwtTokenProvider.generateAccessToken(1L, Role.USER)).willReturn("newAccess");
        given(jwtTokenProvider.generateRefreshToken(1L)).willReturn("newRefresh");
        given(jwtProperties.getRefreshTokenValiditySeconds()).willReturn(1209600L);

        TokenResponse response = authService.reissue(oldRefresh);

        assertThat(response.getAccessToken()).isEqualTo("newAccess");
        assertThat(response.getRefreshToken()).isEqualTo("newRefresh");
        assertThat(stored.getToken()).isEqualTo("newRefresh"); // 회전됨
    }

    @Test
    @DisplayName("재발급 실패 - 토큰 서명/만료 검증 실패면 INVALID_TOKEN")
    void reissue_invalidToken() {
        given(jwtTokenProvider.validateToken("bad")).willReturn(false);

        assertThatThrownBy(() -> authService.reissue("bad"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_TOKEN);
    }

    @Test
    @DisplayName("재발급 실패 - DB에 저장된 토큰이 없으면 INVALID_TOKEN")
    void reissue_notStored() {
        given(jwtTokenProvider.validateToken("refresh")).willReturn(true);
        given(jwtTokenProvider.getUserId("refresh")).willReturn(1L);
        given(refreshTokenRepository.findByUserId(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.reissue("refresh"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_TOKEN);
    }

    @Test
    @DisplayName("재발급 실패 - DB 토큰과 불일치하면 INVALID_TOKEN")
    void reissue_mismatch() {
        RefreshToken stored = RefreshToken.builder()
                .userId(1L).token("otherToken").expiresAt(LocalDateTime.now().plusDays(1)).build();
        given(jwtTokenProvider.validateToken("refresh")).willReturn(true);
        given(jwtTokenProvider.getUserId("refresh")).willReturn(1L);
        given(refreshTokenRepository.findByUserId(1L)).willReturn(Optional.of(stored));

        assertThatThrownBy(() -> authService.reissue("refresh"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_TOKEN);
    }

    @Test
    @DisplayName("로그아웃 - 유저의 RefreshToken을 삭제한다")
    void logout() {
        authService.logout(1L);

        verify(refreshTokenRepository).deleteByUserId(1L);
    }

    private User userWithId(Long id, String email, String password) {
        User user = User.builder()
                .email(email).password(password).nickname("닉네임").role(Role.USER).build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private LoginRequest loginRequest(String email, String password) {
        LoginRequest request = new LoginRequest();
        ReflectionTestUtils.setField(request, "email", email);
        ReflectionTestUtils.setField(request, "password", password);
        return request;
    }
}
