package com.threeam.auth.controller;

import com.threeam.auth.dto.LoginRequest;
import com.threeam.auth.dto.OAuthLoginRequest;
import com.threeam.auth.dto.ReissueRequest;
import com.threeam.auth.dto.TokenResponse;
import com.threeam.auth.service.AuthService;
import com.threeam.user.entity.AuthProvider;
import com.threeam.global.web.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest httpRequest) {
        return ResponseEntity.ok(authService.login(request, ClientIp.of(httpRequest)));
    }

    // 로그인 없이 시작 — 게스트 계정을 만들고 바로 토큰을 준다.
    @PostMapping("/guest")
    public ResponseEntity<TokenResponse> guestStart(HttpServletRequest httpRequest) {
        return ResponseEntity.ok(authService.guestStart(ClientIp.of(httpRequest)));
    }

    // 게스트가 토큰을 지닌 채 소셜 로그인하면(userId 존재) 새 계정 대신 게스트 행을 승격한다.
    @PostMapping("/oauth/{provider}")
    public ResponseEntity<TokenResponse> oauthLogin(@PathVariable String provider,
                                                    @Valid @RequestBody OAuthLoginRequest request,
                                                    @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(
                authService.oauthLogin(AuthProvider.fromOAuthPath(provider), request, userId));
    }

    @PostMapping("/reissue")
    public ResponseEntity<TokenResponse> reissue(@Valid @RequestBody ReissueRequest request) {
        return ResponseEntity.ok(authService.reissue(request.getRefreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Long userId) {
        authService.logout(userId);
        return ResponseEntity.noContent().build();
    }
}
