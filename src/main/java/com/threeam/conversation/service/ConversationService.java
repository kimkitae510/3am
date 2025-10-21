package com.threeam.conversation.service;

import com.threeam.conversation.dto.ConversationCreateRequest;
import com.threeam.conversation.dto.ConversationResponse;
import com.threeam.conversation.entity.Conversation;
import com.threeam.conversation.repository.ConversationRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConversationService {

    private static final String DEFAULT_TITLE = "새 대화";

    private final ConversationRepository conversationRepository;

    @Transactional
    public ConversationResponse create(Long userId, ConversationCreateRequest request) {
        String title = (request.getTitle() == null || request.getTitle().isBlank())
                ? DEFAULT_TITLE
                : request.getTitle().trim();

        Conversation conversation = conversationRepository.save(
                Conversation.builder().userId(userId).title(title).build());

        return ConversationResponse.from(conversation);
    }

    public List<ConversationResponse> getConversations(Long userId) {
        return conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(ConversationResponse::from)
                .toList();
    }
}
