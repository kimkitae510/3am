package com.threeam.story.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.llm.LlmClient;
import com.threeam.story.dto.MessagePageResponse;
import com.threeam.story.dto.MessageResponse;
import com.threeam.story.dto.MessageSendRequest;
import com.threeam.story.dto.StoryCreateRequest;
import com.threeam.story.dto.StoryResponse;
import com.threeam.story.entity.Message;
import com.threeam.story.entity.MessageRole;
import com.threeam.story.entity.Story;
import com.threeam.story.repository.MessageRepository;
import com.threeam.story.repository.StoryMemoryRepository;
import com.threeam.story.repository.StoryRepository;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class StoryServiceTest {

    @Mock
    private StoryRepository storyRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private StoryMemoryRepository storyMemoryRepository;

    @Mock
    private MessageTxService messageTxService;

    @Mock
    private LlmClient llmClient;

    @InjectMocks
    private StoryService storyService;

    @Test
    @DisplayName("사연 생성 - 제목을 지정하면 그대로 저장한다")
    void create_withTitle() {
        given(storyRepository.save(any(Story.class))).willAnswer(inv -> inv.getArgument(0));

        StoryResponse response = storyService.create(1L, createRequest("힘든 밤"));

        assertThat(response.getTitle()).isEqualTo("힘든 밤");
        verify(storyRepository).save(any(Story.class));
    }

    @Test
    @DisplayName("사연 생성 - 제목이 비어 있으면 기본 제목을 붙인다")
    void create_defaultTitle() {
        given(storyRepository.save(any(Story.class))).willAnswer(inv -> inv.getArgument(0));

        StoryResponse response = storyService.create(1L, createRequest("  "));

        assertThat(response.getTitle()).isEqualTo("새 대화");
    }

    @Test
    @DisplayName("사연 목록 - 유저의 사연을 최근 활동순으로 반환한다")
    void getStories_success() {
        given(storyRepository.findByUserIdOrderByUpdatedAtDesc(1L))
                .willReturn(List.of(story(1L, "첫 사연"), story(1L, "둘째 사연")));

        List<StoryResponse> responses = storyService.getStories(1L);

        assertThat(responses).extracting(StoryResponse::getTitle)
                .containsExactly("첫 사연", "둘째 사연");
    }

    @Test
    @DisplayName("메시지 전송 - 유저 저장→LLM(논블로킹)→어시스턴트 저장 순으로 오케스트레이션한다")
    void sendMessage_success() {
        given(messageTxService.appendUserMessageAndBuildPrompt(1L, 10L, "오늘 너무 힘들어"))
                .willReturn(List.of());
        given(llmClient.generate(anyList()))
                .willReturn(CompletableFuture.completedFuture("괜찮아요, 여기 있어요."));
        Message answer = message(2L, MessageRole.ASSISTANT, "괜찮아요, 여기 있어요.");
        given(messageTxService.appendAssistantReply(10L, "괜찮아요, 여기 있어요."))
                .willReturn(MessageResponse.from(answer));

        MessageResponse response = storyService.sendMessage(1L, 10L, sendRequest("오늘 너무 힘들어")).join();

        assertThat(response.getRole()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(response.getContent()).isEqualTo("괜찮아요, 여기 있어요.");
        verify(llmClient).generate(anyList());
    }

    @Test
    @DisplayName("메시지 전송 - 없거나 남의 사연이면 STORY_NOT_FOUND, LLM을 호출하지 않는다")
    void sendMessage_notFound() {
        given(messageTxService.appendUserMessageAndBuildPrompt(1L, 10L, "hi"))
                .willThrow(new BusinessException(ErrorCode.STORY_NOT_FOUND));

        assertThatThrownBy(() -> storyService.sendMessage(1L, 10L, sendRequest("hi")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STORY_NOT_FOUND);

        verify(llmClient, never()).generate(anyList());
    }

    @Test
    @DisplayName("메시지 조회 - 커서 없이 최신 페이지를 과거→현재 순으로 반환한다")
    void getMessages_firstPage() {
        Story story = story(1L, "사연");
        given(storyRepository.findByIdAndUserId(10L, 1L)).willReturn(Optional.of(story));
        Message older = message(1L, MessageRole.USER, "안녕");
        Message newer = message(2L, MessageRole.ASSISTANT, "안녕하세요");
        // 조회는 id 역순(최신 먼저)으로 온다. hasNext=true(더 과거 있음)로 가정.
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(10L), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(newer, older), PageRequest.of(0, 30), true));

        MessagePageResponse response = storyService.getMessages(1L, 10L, null, 30);

        assertThat(response.getMessages()).extracting(MessageResponse::getContent)
                .containsExactly("안녕", "안녕하세요"); // 과거→현재로 뒤집혀 나온다
        assertThat(response.getNextCursor()).isEqualTo(1L); // 이번 배치에서 가장 오래된 id
        assertThat(response.isHasNext()).isTrue();
    }

    @Test
    @DisplayName("메시지 조회 - 없거나 남의 사연이면 STORY_NOT_FOUND")
    void getMessages_notFound() {
        given(storyRepository.findByIdAndUserId(10L, 1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> storyService.getMessages(1L, 10L, null, 30))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STORY_NOT_FOUND);
    }

    @Test
    @DisplayName("사연 삭제 - 메시지를 먼저 지우고 사연을 삭제한다")
    void deleteStory_success() {
        Story story = story(1L, "사연");
        given(storyRepository.findByIdAndUserId(10L, 1L)).willReturn(Optional.of(story));

        storyService.deleteStory(1L, 10L);

        verify(messageRepository).deleteByStoryId(10L);
        verify(storyRepository).delete(story);
    }

    @Test
    @DisplayName("사연 삭제 - 없거나 남의 사연이면 STORY_NOT_FOUND, 삭제하지 않는다")
    void deleteStory_notFound() {
        given(storyRepository.findByIdAndUserId(10L, 1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> storyService.deleteStory(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STORY_NOT_FOUND);

        verify(messageRepository, never()).deleteByStoryId(any());
        verify(storyRepository, never()).delete(any(Story.class));
    }

    private Story story(Long userId, String title) {
        return Story.builder().userId(userId).title(title).build();
    }

    private Message message(Long id, MessageRole role, String content) {
        Message message = Message.builder().role(role).content(content).build();
        ReflectionTestUtils.setField(message, "id", id);
        return message;
    }

    private StoryCreateRequest createRequest(String title) {
        StoryCreateRequest request = new StoryCreateRequest();
        ReflectionTestUtils.setField(request, "title", title);
        return request;
    }

    private MessageSendRequest sendRequest(String content) {
        MessageSendRequest request = new MessageSendRequest();
        ReflectionTestUtils.setField(request, "content", content);
        return request;
    }
}
