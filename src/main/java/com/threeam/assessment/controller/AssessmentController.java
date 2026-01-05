package com.threeam.assessment.controller;

import com.threeam.assessment.dto.AssessmentResponse;
import com.threeam.assessment.service.AssessmentService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stories/{storyId}/assessments")
@RequiredArgsConstructor
public class AssessmentController {

    private final AssessmentService assessmentService;

    // 입력 폼 없이 사연의 대화를 읽어 진단한다. LLM 호출이 끼므로 논블로킹으로 반환한다.
    @PostMapping
    public CompletableFuture<ResponseEntity<AssessmentResponse>> assess(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long storyId) {
        return assessmentService.assess(userId, storyId)
                .thenApply(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    @GetMapping
    public ResponseEntity<List<AssessmentResponse>> getHistory(@AuthenticationPrincipal Long userId,
                                                               @PathVariable Long storyId) {
        return ResponseEntity.ok(assessmentService.getHistory(userId, storyId));
    }

    // "사귀는 중" 잠금을 유저가 직접 번복한다(진단이 오해했을 수 있다). 원장에 확인 기록만 남고,
    // 확률은 헤어진 경위를 대화한 뒤의 다음 진단에서 열린다.
    @PostMapping("/confirm-breakup")
    public ResponseEntity<Void> confirmBreakup(@AuthenticationPrincipal Long userId,
                                               @PathVariable Long storyId) {
        assessmentService.confirmBreakup(userId, storyId);
        return ResponseEntity.noContent().build();
    }

    // "상대의 재회 제안 유효(100%)" 확정을 유저가 직접 번복한다(제안이 아니었거나 없던 일이 됨).
    // 마찬가지로 원장에 정정 기록만 남고, 확률은 다음 진단에서 일반 합산으로 돌아간다.
    @PostMapping("/retract-offer")
    public ResponseEntity<Void> retractOffer(@AuthenticationPrincipal Long userId,
                                             @PathVariable Long storyId) {
        assessmentService.retractOffer(userId, storyId);
        return ResponseEntity.noContent().build();
    }
}
