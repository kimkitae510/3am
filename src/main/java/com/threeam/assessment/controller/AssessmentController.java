package com.threeam.assessment.controller;

import com.threeam.assessment.dto.AssessmentRequest;
import com.threeam.assessment.dto.AssessmentResponse;
import com.threeam.assessment.service.AssessmentService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stories/{storyId}/assessments")
@RequiredArgsConstructor
public class AssessmentController {

    private final AssessmentService assessmentService;

    @PostMapping
    public ResponseEntity<AssessmentResponse> assess(@AuthenticationPrincipal Long userId,
                                                     @PathVariable Long storyId,
                                                     @Valid @RequestBody AssessmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(assessmentService.assess(userId, storyId, request));
    }

    @GetMapping
    public ResponseEntity<List<AssessmentResponse>> getHistory(@AuthenticationPrincipal Long userId,
                                                               @PathVariable Long storyId) {
        return ResponseEntity.ok(assessmentService.getHistory(userId, storyId));
    }
}
