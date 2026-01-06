package com.threeam.usage;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/usage")
@RequiredArgsConstructor
public class UsageController {

    private final UsageLimiter usageLimiter;

    @GetMapping
    public ResponseEntity<UsageStatusResponse> getMyUsage(@AuthenticationPrincipal Long userId) {
        // 한도는 유저별로 다를 수 있어(가입 당일 상향) 고정 설정이 아니라 리미터에게 묻는다.
        return ResponseEntity.ok(new UsageStatusResponse(
                usageLimiter.remainingDaily(UsageKind.CHAT, userId),
                usageLimiter.dailyLimit(UsageKind.CHAT, userId),
                usageLimiter.paidRemaining(UsageKind.CHAT, userId),
                usageLimiter.remainingDaily(UsageKind.ASSESSMENT, userId),
                usageLimiter.dailyLimit(UsageKind.ASSESSMENT, userId),
                usageLimiter.paidRemaining(UsageKind.ASSESSMENT, userId)));
    }
}
