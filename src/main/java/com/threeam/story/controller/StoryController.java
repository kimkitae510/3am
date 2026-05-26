package com.threeam.story.controller;

import com.threeam.story.dto.MessagePageResponse;
import com.threeam.story.dto.MessageResponse;
import com.threeam.story.dto.MessageRetryResponse;
import com.threeam.story.dto.MessageSendRequest;
import com.threeam.story.dto.StoryCreateRequest;
import com.threeam.story.dto.StoryFactRequest;
import com.threeam.story.dto.StoryResponse;
import com.threeam.story.service.StoryFactService;
import com.threeam.story.service.StoryService;
import jakarta.validation.Valid;
import java.util.List;
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
    private final StoryFactService storyFactService;

    @PostMapping
    public ResponseEntity<StoryResponse> create(@AuthenticationPrincipal Long userId,
                                                @Valid @RequestBody StoryCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(storyService.create(userId, request));
    }

    @GetMapping
    public ResponseEntity<List<StoryResponse>> getStories(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(storyService.getStories(userId));
    }

    // 폴링 방식: 유저 메시지만 저장하고 즉시 202로 반환한다. 어시스턴트 답은 백그라운드 생성 →
    // 클라이언트가 GET .../messages/since?after=<반환된 id>로 폴링해 받아간다.
    @PostMapping("/{storyId}/messages")
    public ResponseEntity<MessageResponse> sendMessage(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long storyId,
            @Valid @RequestBody MessageSendRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(storyService.sendMessage(userId, storyId, request));
    }

    // 답을 못 받은 턴(폴백 말풍선)의 재시도. 유저 메시지는 그대로 두고 답만 다시 만든다 —
    // 같은 말을 다시 치게 하지 않으려는 것이라 새 메시지를 받지 않는다(본문 없음).
    @PostMapping("/{storyId}/messages/retry")
    public ResponseEntity<MessageRetryResponse> retryLastReply(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long storyId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(storyService.retryLastReply(userId, storyId));
    }

    @GetMapping("/{storyId}/messages")
    public ResponseEntity<MessagePageResponse> getMessages(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long storyId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "30") int size) {
        return ResponseEntity.ok(storyService.getMessages(userId, storyId, cursor, size));
    }

    @GetMapping("/{storyId}/messages/since")
    public ResponseEntity<List<MessageResponse>> getMessagesSince(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long storyId,
            @RequestParam Long after) {
        return ResponseEntity.ok(storyService.getMessagesSince(userId, storyId, after));
    }

    // 진단 화면의 "사실 직접 알려주기" — 채팅 없이 원장에 사실을 보태고 재진단 가드를 통과시킨다.
    @PostMapping("/{storyId}/facts")
    public ResponseEntity<Void> addFact(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long storyId,
            @Valid @RequestBody StoryFactRequest request) {
        storyFactService.appendUserFact(userId, storyId, request.content());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/{storyId}")
    public ResponseEntity<Void> deleteStory(@AuthenticationPrincipal Long userId,
                                            @PathVariable Long storyId) {
        storyService.deleteStory(userId, storyId);
        return ResponseEntity.noContent().build();
    }
}
