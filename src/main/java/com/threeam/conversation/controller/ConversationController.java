package com.threeam.conversation.controller;

import com.threeam.conversation.dto.ConversationCreateRequest;
import com.threeam.conversation.dto.ConversationResponse;
import com.threeam.conversation.dto.MessagePageResponse;
import com.threeam.conversation.dto.MessageResponse;
import com.threeam.conversation.dto.MessageSendRequest;
import com.threeam.conversation.service.ConversationService;
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
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    @PostMapping
    public ResponseEntity<ConversationResponse> create(@AuthenticationPrincipal Long userId,
                                                       @Valid @RequestBody ConversationCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(conversationService.create(userId, request));
    }

    @GetMapping
    public ResponseEntity<List<ConversationResponse>> getConversations(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(conversationService.getConversations(userId));
    }

    @PostMapping("/{conversationId}/messages")
    public ResponseEntity<MessageResponse> sendMessage(@AuthenticationPrincipal Long userId,
                                                       @PathVariable Long conversationId,
                                                       @Valid @RequestBody MessageSendRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(conversationService.sendMessage(userId, conversationId, request));
    }

    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<MessagePageResponse> getMessages(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long conversationId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "30") int size) {
        return ResponseEntity.ok(conversationService.getMessages(userId, conversationId, cursor, size));
    }

    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Void> deleteConversation(@AuthenticationPrincipal Long userId,
                                                   @PathVariable Long conversationId) {
        conversationService.deleteConversation(userId, conversationId);
        return ResponseEntity.noContent().build();
    }
}
