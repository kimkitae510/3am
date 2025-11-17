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
    private final UsageProperties properties;

    @GetMapping
    public ResponseEntity<UsageStatusResponse> getMyUsage(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(new UsageStatusResponse(
                usageLimiter.remainingDaily(UsageKind.CHAT, userId),
                properties.getChatDailyLimit(),
                usageLimiter.remainingDaily(UsageKind.ASSESSMENT, userId),
                properties.getAssessmentDailyLimit()));
    }
}
