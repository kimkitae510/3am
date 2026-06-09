package com.threeam.review.controller;

import com.threeam.review.dto.ReviewCommentRequest;
import com.threeam.review.dto.ReviewStatusResponse;
import com.threeam.review.dto.ReviewSubmitRequest;
import com.threeam.review.dto.ReviewSubmitResponse;
import com.threeam.review.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 경로가 단수(review)인 이유: 평가 대상이 "그 사연의 최신 진단" 하나뿐이라 목록이 없다.
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

    @PostMapping
    public ResponseEntity<ReviewSubmitResponse> submit(@AuthenticationPrincipal Long userId,
                                                       @PathVariable Long storyId,
                                                       @RequestBody ReviewSubmitRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reviewService.submit(userId, storyId, request));
    }

    @PutMapping("/comment")
    public ResponseEntity<Void> comment(@AuthenticationPrincipal Long userId,
                                        @PathVariable Long storyId,
                                        @RequestBody ReviewCommentRequest request) {
        reviewService.addComment(userId, storyId, request);
        return ResponseEntity.noContent().build();
    }
}
