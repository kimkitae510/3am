package com.threeam.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.threeam.conversation.dto.ConversationCreateRequest;
import com.threeam.conversation.dto.ConversationResponse;
import com.threeam.conversation.dto.MessageResponse;
import com.threeam.conversation.dto.MessageSendRequest;
import com.threeam.conversation.entity.Conversation;
import com.threeam.conversation.entity.Message;
import com.threeam.conversation.entity.MessageRole;
import com.threeam.conversation.repository.ConversationRepository;
import com.threeam.conversation.repository.MessageRepository;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.llm.LlmClient;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private LlmClient llmClient;

    @InjectMocks
    private ConversationService conversationService;

    @Test
    @DisplayName("대화 생성 - 제목을 지정하면 그대로 저장한다")
    void create_withTitle() {
        given(conversationRepository.save(any(Conversation.class))).willAnswer(inv -> inv.getArgument(0));

        ConversationResponse response = conversationService.create(1L, createRequest("힘든 밤"));

        assertThat(response.getTitle()).isEqualTo("힘든 밤");
        verify(conversationRepository).save(any(Conversation.class));
    }

    @Test
    @DisplayName("대화 생성 - 제목이 비어 있으면 기본 제목을 붙인다")
    void create_defaultTitle() {
        given(conversationRepository.save(any(Conversation.class))).willAnswer(inv -> inv.getArgument(0));

        ConversationResponse response = conversationService.create(1L, createRequest("  "));

        assertThat(response.getTitle()).isEqualTo("새 대화");
    }

    @Test
    @DisplayName("대화 목록 - 유저의 대화를 최근 활동순으로 반환한다")
    void getConversations_success() {
        given(conversationRepository.findByUserIdOrderByUpdatedAtDesc(1L))
                .willReturn(List.of(conversation(1L, "첫 대화"), conversation(1L, "둘째 대화")));

        List<ConversationResponse> responses = conversationService.getConversations(1L);

        assertThat(responses).extracting(ConversationResponse::getTitle)
                .containsExactly("첫 대화", "둘째 대화");
    }

    @Test
    @DisplayName("메시지 전송 - 유저/어시스턴트 메시지를 저장하고 LLM 응답을 반환한다")
    void sendMessage_success() {
        Conversation conversation = conversation(1L, "대화");
        given(conversationRepository.findByIdAndUserId(10L, 1L)).willReturn(Optional.of(conversation));
        given(messageRepository.save(any(Message.class))).willAnswer(inv -> inv.getArgument(0));
        given(messageRepository.findByConversationIdOrderByCreatedAtDesc(eq(10L), any(Pageable.class)))
                .willReturn(List.of());
        given(llmClient.generate(anyList())).willReturn("괜찮아요, 여기 있어요.");

        MessageResponse response = conversationService.sendMessage(1L, 10L, sendRequest("오늘 너무 힘들어"));

        assertThat(response.getRole()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(response.getContent()).isEqualTo("괜찮아요, 여기 있어요.");
        verify(messageRepository, times(2)).save(any(Message.class)); // 유저 + 어시스턴트
        verify(llmClient).generate(anyList());
    }

    @Test
    @DisplayName("메시지 전송 - 없거나 남의 대화면 CONVERSATION_NOT_FOUND, LLM을 호출하지 않는다")
    void sendMessage_notFound() {
        given(conversationRepository.findByIdAndUserId(10L, 1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> conversationService.sendMessage(1L, 10L, sendRequest("hi")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONVERSATION_NOT_FOUND);

        verify(messageRepository, never()).save(any(Message.class));
        verify(llmClient, never()).generate(anyList());
    }

    @Test
    @DisplayName("메시지 조회 - 없거나 남의 대화면 CONVERSATION_NOT_FOUND")
    void getMessages_notFound() {
        given(conversationRepository.findByIdAndUserId(10L, 1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> conversationService.getMessages(1L, 10L, Pageable.ofSize(50)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONVERSATION_NOT_FOUND);
    }

    @Test
    @DisplayName("대화 삭제 - 메시지를 먼저 지우고 대화를 삭제한다")
    void deleteConversation_success() {
        Conversation conversation = conversation(1L, "대화");
        given(conversationRepository.findByIdAndUserId(10L, 1L)).willReturn(Optional.of(conversation));

        conversationService.deleteConversation(1L, 10L);

        verify(messageRepository).deleteByConversationId(10L);
        verify(conversationRepository).delete(conversation);
    }

    @Test
    @DisplayName("대화 삭제 - 없거나 남의 대화면 CONVERSATION_NOT_FOUND, 삭제하지 않는다")
    void deleteConversation_notFound() {
        given(conversationRepository.findByIdAndUserId(10L, 1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> conversationService.deleteConversation(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONVERSATION_NOT_FOUND);

        verify(messageRepository, never()).deleteByConversationId(any());
        verify(conversationRepository, never()).delete(any(Conversation.class));
    }

    private Conversation conversation(Long userId, String title) {
        return Conversation.builder().userId(userId).title(title).build();
    }

    private ConversationCreateRequest createRequest(String title) {
        ConversationCreateRequest request = new ConversationCreateRequest();
        ReflectionTestUtils.setField(request, "title", title);
        return request;
    }

    private MessageSendRequest sendRequest(String content) {
        MessageSendRequest request = new MessageSendRequest();
        ReflectionTestUtils.setField(request, "content", content);
        return request;
    }
}
