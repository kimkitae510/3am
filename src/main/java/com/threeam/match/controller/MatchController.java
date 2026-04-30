package com.threeam.match.controller;

import com.threeam.match.dto.SimilarCasesResponse;
import com.threeam.match.service.MatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stories/{storyId}/similar-cases")
@RequiredArgsConstructor
public class MatchController {

    private final MatchService matchService;

    // 사례가 없어도 200이다 — 닮은 사례가 없는 건 오류가 아니라 결과이고,
    // 화면은 emptyReason으로 "대화를 더 해달라"와 "아직 데이터가 부족하다"를 갈라 말한다.
    @GetMapping
    public ResponseEntity<SimilarCasesResponse> findSimilar(@AuthenticationPrincipal Long userId,
                                                            @PathVariable Long storyId) {
        return ResponseEntity.ok(matchService.findSimilar(userId, storyId));
    }
}
