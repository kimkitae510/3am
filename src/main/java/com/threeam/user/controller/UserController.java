package com.threeam.user.controller;

import com.threeam.global.web.ClientIp;
import com.threeam.user.dto.EmailVerificationRequest;
import com.threeam.user.dto.PasswordChangeRequest;
import com.threeam.user.dto.SignupRequest;
import com.threeam.user.dto.SignupResponse;
import com.threeam.user.dto.UserMeResponse;
import com.threeam.user.service.EmailVerificationService;
import com.threeam.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final EmailVerificationService emailVerificationService;

    @PostMapping("/email-verifications")
    public ResponseEntity<Void> issueVerification(@Valid @RequestBody EmailVerificationRequest request,
                                                  HttpServletRequest httpRequest) {
        emailVerificationService.issue(request.getEmail(), ClientIp.of(httpRequest));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest request,
                                                 HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(userService.signup(request, ClientIp.of(httpRequest)));
    }

    // 게스트를 이메일 계정으로 승격 — 가입과 같은 검증(인증 코드, 동의)을 거치되 새 계정을
    // 만들지 않고 현재 게스트 행을 교체한다. 로그인 상태(토큰)는 그대로 유효하다.
    @PostMapping("/guest-link")
    public ResponseEntity<Void> linkGuestEmail(@AuthenticationPrincipal Long userId,
                                               @Valid @RequestBody SignupRequest request) {
        userService.linkGuestEmail(userId, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<UserMeResponse> me(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(userService.me(userId));
    }

    @PatchMapping("/me/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal Long userId,
                                               @Valid @RequestBody PasswordChangeRequest request) {
        userService.changePassword(userId, request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal Long userId) {
        userService.withdraw(userId);
        return ResponseEntity.noContent().build();
    }
}
