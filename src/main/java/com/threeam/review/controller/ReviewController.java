package com.threeam.review.controller;

import com.threeam.review.dto.ReviewCommentRequest;
import com.threeam.review.dto.ReviewStatusResponse;
import com.threeam.review.dto.ReviewSubmitRequest;
import com.threeam.review.dto.ReviewSubmitResponse;
import com.threeam.review.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 경로가 단수(review)인 이유: 평가 대상이 "그 사연의 최신 분석" 하나뿐이라 목록이 없다.
@RestController
@RequestMapping("/api/stories/{storyId}/review")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @GetMapping
    public ResponseEntity<ReviewStatusResponse> status(@AuthenticationPrincipal Long userId,
                                                       @PathVariable Long storyId) {
        return ResponseEntity.ok(reviewService.status(userId, storyId));
    }

    // 점수는 업서트라 만들기와 고치기가 한 경로다 — 본문 없는 204로 답한다.
    @PostMapping
    public ResponseEntity<Void> submit(@AuthenticationPrincipal Long userId,
                                       @PathVariable Long storyId,
                                       @RequestBody ReviewSubmitRequest request) {
        reviewService.submitScore(userId, storyId, request);
        return ResponseEntity.noContent().build();
    }

    // 보상(후기 완성 시 유저당 1회)이 여기서 나가므로 지급량을 본문으로 돌려준다(미지급이면 0).
    @PutMapping("/comment")
    public ResponseEntity<ReviewSubmitResponse> comment(@AuthenticationPrincipal Long userId,
                                                        @PathVariable Long storyId,
                                                        @RequestBody ReviewCommentRequest request) {
        return ResponseEntity.ok(reviewService.addComment(userId, storyId, request));
    }
}
