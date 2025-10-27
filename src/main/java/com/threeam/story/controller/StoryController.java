package com.threeam.story.controller;

import com.threeam.story.dto.MessagePageResponse;
import com.threeam.story.dto.MessageResponse;
import com.threeam.story.dto.MessageSendRequest;
import com.threeam.story.dto.StoryCreateRequest;
import com.threeam.story.dto.StoryResponse;
import com.threeam.story.service.StoryService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stories")
@RequiredArgsConstructor
public class StoryController {

    private final StoryService storyService;

    @PostMapping
    public ResponseEntity<StoryResponse> create(@AuthenticationPrincipal Long userId,
                                                @Valid @RequestBody StoryCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(storyService.create(userId, request));
    }

    @GetMapping
    public ResponseEntity<List<StoryResponse>> getStories(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(storyService.getStories(userId));
    }

    @PostMapping("/{storyId}/messages")
    public CompletableFuture<ResponseEntity<MessageResponse>> sendMessage(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long storyId,
            @Valid @RequestBody MessageSendRequest request) {
        // 논블로킹: CompletableFuture를 반환하면 LLM 응답을 기다리는 동안 서블릿 스레드가 반납된다.
        return storyService.sendMessage(userId, storyId, request)
                .thenApply(message -> ResponseEntity.status(HttpStatus.CREATED).body(message));
    }

    @GetMapping("/{storyId}/messages")
    public ResponseEntity<MessagePageResponse> getMessages(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long storyId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "30") int size) {
        return ResponseEntity.ok(storyService.getMessages(userId, storyId, cursor, size));
    }

    @DeleteMapping("/{storyId}")
    public ResponseEntity<Void> deleteStory(@AuthenticationPrincipal Long userId,
                                            @PathVariable Long storyId) {
        storyService.deleteStory(userId, storyId);
        return ResponseEntity.noContent().build();
    }
}
