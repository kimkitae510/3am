package com.threeam.conversation.service;

import com.threeam.conversation.dto.ConversationCreateRequest;
import com.threeam.conversation.dto.ConversationResponse;
import com.threeam.conversation.dto.MessagePageResponse;
import com.threeam.conversation.dto.MessageResponse;
import com.threeam.conversation.dto.MessageSendRequest;
import com.threeam.conversation.entity.Conversation;
import com.threeam.conversation.entity.Message;
import com.threeam.conversation.entity.MessageRole;
import com.threeam.conversation.repository.ConversationRepository;
import com.threeam.conversation.repository.MessageRepository;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.llm.ChatMessage;
import com.threeam.llm.LlmClient;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConversationService {

    private static final String DEFAULT_TITLE = "새 대화";

    // LLM에 실어 보낼 직전 맥락의 크기(메시지 수). 토큰·비용을 제한하기 위한 window.
    private static final int HISTORY_WINDOW = 20;

    // 조회 한 번에 내려줄 메시지 수 상한. 클라가 큰 size를 넘겨도 여기서 자른다.
    private static final int MAX_PAGE_SIZE = 100;

    // 페르소나 실문구는 저장소 밖에서 관리한다(CLAUDE.md). 여기서는 자리표시자만 둔다.
    private static final String SYSTEM_PROMPT = "당신은 이별을 겪은 사람의 곁을 지키는 다정한 대화 상대입니다.";

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final LlmClient llmClient;

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

    @Transactional
    public MessageResponse sendMessage(Long userId, Long conversationId, MessageSendRequest request) {
        Conversation conversation = findOwned(conversationId, userId);

        messageRepository.save(Message.user(conversation, request.getContent()));

        String reply = llmClient.generate(buildPrompt(conversationId));

        Message answer = messageRepository.save(Message.assistant(conversation, reply));
        conversation.touch();

        return MessageResponse.from(answer);
    }

    public MessagePageResponse getMessages(Long userId, Long conversationId, Long cursor, int size) {
        findOwned(conversationId, userId);

        int limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest page = PageRequest.of(0, limit);
        Slice<Message> slice = (cursor == null)
                ? messageRepository.findByConversationIdOrderByIdDesc(conversationId, page)
                : messageRepository.findByConversationIdAndIdLessThanOrderByIdDesc(conversationId, cursor, page);

        // 조회는 최신→과거(id desc)로 오지만, 화면 표시용으로 과거→현재로 뒤집는다.
        List<Message> content = slice.getContent();
        List<MessageResponse> messages = new ArrayList<>();
        for (int i = content.size() - 1; i >= 0; i--) {
            messages.add(MessageResponse.from(content.get(i)));
        }
        // 다음 커서 = 이번 배치에서 가장 오래된(가장 작은) id. 클라는 이보다 과거를 이어서 요청한다.
        Long nextCursor = content.isEmpty() ? null : content.get(content.size() - 1).getId();

        return new MessagePageResponse(messages, nextCursor, slice.hasNext());
    }

    @Transactional
    public void deleteConversation(Long userId, Long conversationId) {
        Conversation conversation = findOwned(conversationId, userId);
        messageRepository.deleteByConversationId(conversationId);
        conversationRepository.delete(conversation);
    }

    private Conversation findOwned(Long conversationId, Long userId) {
        return conversationRepository.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND));
    }

    private List<ChatMessage> buildPrompt(Long conversationId) {
        // 방금 저장한 유저 메시지까지 포함해 최신순 N개를 가져온 뒤, 시간순으로 뒤집어 대화 순서를 복원한다.
        List<Message> recent = messageRepository
                .findByConversationIdOrderByIdDesc(conversationId, PageRequest.of(0, HISTORY_WINDOW))
                .getContent();

        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(ChatMessage.system(SYSTEM_PROMPT));
        for (int i = recent.size() - 1; i >= 0; i--) {
            Message message = recent.get(i);
            prompt.add(message.getRole() == MessageRole.USER
                    ? ChatMessage.user(message.getContent())
                    : ChatMessage.assistant(message.getContent()));
        }
        return prompt;
    }
}
