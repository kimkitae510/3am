package com.threeam.usage;

import com.threeam.user.entity.User;
import com.threeam.user.repository.UserRepository;
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
    private final UserRepository userRepository;
    private final ChatRetryGuard chatRetryGuard;

    @GetMapping
    public ResponseEntity<UsageStatusResponse> getMyUsage(@AuthenticationPrincipal Long userId) {
        boolean guest = userRepository.findById(userId).map(User::isGuest).orElse(false);
        return ResponseEntity.ok(new UsageStatusResponse(
                usageLimiter.remaining(UsageKind.CHAT, userId),
                usageLimiter.remaining(UsageKind.ASSESSMENT, userId),
                guest,
                chatRetryGuard.blockedSeconds(userId)));
    }
}
