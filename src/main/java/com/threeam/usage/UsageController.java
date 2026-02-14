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

    @GetMapping
    public ResponseEntity<UsageStatusResponse> getMyUsage(@AuthenticationPrincipal Long userId) {
        boolean guest = userRepository.findById(userId).map(User::isGuest).orElse(false);
        // 한도는 유저별로 다를 수 있어(게스트 총량, 회원 일일) 고정 설정이 아니라 리미터에게 묻는다.
        return ResponseEntity.ok(new UsageStatusResponse(
                usageLimiter.remainingDaily(UsageKind.CHAT, userId),
                usageLimiter.dailyLimit(UsageKind.CHAT, userId),
                usageLimiter.paidRemaining(UsageKind.CHAT, userId),
                usageLimiter.remainingDaily(UsageKind.ASSESSMENT, userId),
                usageLimiter.dailyLimit(UsageKind.ASSESSMENT, userId),
                usageLimiter.paidRemaining(UsageKind.ASSESSMENT, userId),
                guest));
    }
}
