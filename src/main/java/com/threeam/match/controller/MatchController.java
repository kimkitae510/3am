package com.threeam.match.controller;

import com.threeam.match.dto.PickedCasesResponse;
import com.threeam.match.dto.SimilarCasesResponse;
import com.threeam.match.service.MatchService;
import com.threeam.match.service.PaidMatchService;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stories/{storyId}/similar-cases")
@RequiredArgsConstructor
public class MatchController {

    private final MatchService matchService;
    private final PaidMatchService paidMatchService;

    // 사례가 없어도 200이다 — 닮은 사례가 없는 건 오류가 아니라 결과이고,
    // 화면은 emptyReason으로 "대화를 더 해달라"와 "아직 데이터가 부족하다"를 갈라 말한다.
    @GetMapping
    public ResponseEntity<SimilarCasesResponse> findSimilar(@AuthenticationPrincipal Long userId,
                                                            @PathVariable Long storyId) {
        return ResponseEntity.ok(matchService.findSimilar(userId, storyId));
    }

    // 유료 매칭을 이미 돌렸는지. locked=true면 아직이라 화면이 안내와 버튼을 그린다.
    // 조회는 쿼터를 안 쓴다 — 새로고침이 돈이 되면 안 된다.
    @GetMapping("/picked")
    public ResponseEntity<PickedCasesResponse> peekPicked(@AuthenticationPrincipal Long userId,
                                                          @PathVariable Long storyId) {
        return ResponseEntity.ok(paidMatchService.peek(userId, storyId));
    }

    // 유료 매칭 실행. 이미 돌린 진단이면 저장분을 그대로 주고 쿼터를 안 쓴다.
    // POST인 이유: 쿼터를 쓰고 결과를 남기는 호출이라 GET의 안전성 약속과 어긋난다.
    @PostMapping("/picked")
    public CompletableFuture<PickedCasesResponse> pick(@AuthenticationPrincipal Long userId,
                                                       @PathVariable Long storyId) {
        return paidMatchService.pick(userId, storyId);
    }
}
