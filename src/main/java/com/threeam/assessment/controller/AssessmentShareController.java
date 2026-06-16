package com.threeam.assessment.controller;

import com.threeam.assessment.dto.ShareCreateResponse;
import com.threeam.assessment.dto.SharedAssessmentResponse;
import com.threeam.assessment.service.AssessmentShareService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AssessmentShareController {

    private final AssessmentShareService shareService;

    // 결과 화면의 공유하기 — 최신 분석의 공유 토큰을 만든다(이미 있으면 재사용).
    @PostMapping("/api/stories/{storyId}/assessments/share")
    public ResponseEntity<ShareCreateResponse> create(@AuthenticationPrincipal Long userId,
                                                      @PathVariable Long storyId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(shareService.create(userId, storyId));
    }

    // 살아 있는 링크가 있는지. 화면이 "공유 중"과 취소 버튼을 그릴지 판단한다(없으면 token=null).
    @GetMapping("/api/stories/{storyId}/assessments/share")
    public ResponseEntity<ShareCreateResponse> getActive(@AuthenticationPrincipal Long userId,
                                                         @PathVariable Long storyId) {
        return ResponseEntity.ok(new ShareCreateResponse(shareService.activeToken(userId, storyId)));
    }

    // 공유 취소 — 이 사연의 살아 있는 링크를 전부 끈다. 이미 뿌린 주소는 그 순간부터 안 열린다.
    @DeleteMapping("/api/stories/{storyId}/assessments/share")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal Long userId,
                                       @PathVariable Long storyId) {
        shareService.revoke(userId, storyId);
        return ResponseEntity.noContent().build();
    }

    // 공유 링크의 공개 조회 — 링크를 받은 비회원이 여는 화면이라 인증이 없다(SecurityConfig).
    @GetMapping("/api/share/{token}")
    public ResponseEntity<SharedAssessmentResponse> getShared(@PathVariable String token) {
        return ResponseEntity.ok(shareService.getShared(token));
    }
}
