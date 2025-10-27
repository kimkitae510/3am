package com.threeam.story.service;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.llm.ChatMessage;
import com.threeam.llm.LlmClient;
import com.threeam.story.dto.MessagePageResponse;
import com.threeam.story.dto.MessageResponse;
import com.threeam.story.dto.MessageSendRequest;
import com.threeam.story.dto.StoryCreateRequest;
import com.threeam.story.dto.StoryResponse;
import com.threeam.story.entity.Message;
import com.threeam.story.entity.Story;
import com.threeam.story.repository.MessageRepository;
import com.threeam.story.repository.StoryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoryService {

    private static final String DEFAULT_TITLE = "새 대화";

    // 조회 한 번에 내려줄 메시지 수 상한. 클라가 큰 size를 넘겨도 여기서 자른다.
    private static final int MAX_PAGE_SIZE = 100;

    private final StoryRepository storyRepository;
    private final MessageRepository messageRepository;
    private final MessageTxService messageTxService;
    private final LlmClient llmClient;

    @Transactional
    public StoryResponse create(Long userId, StoryCreateRequest request) {
        String title = (request.getTitle() == null || request.getTitle().isBlank())
                ? DEFAULT_TITLE
                : request.getTitle().trim();

        Story story = storyRepository.save(Story.builder().userId(userId).title(title).build());

        return StoryResponse.from(story);
    }

    public List<StoryResponse> getStories(Long userId) {
        return storyRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(StoryResponse::from)
                .toList();
    }

    // 트랜잭션 밖(NOT_SUPPORTED)에서 오케스트레이션한다.
    // DB 저장은 messageTxService의 짧은 트랜잭션으로, 느린 LLM 호출은 그 사이에서 논블로킹으로.
    // → LLM 응답을 기다리는 동안 DB 커넥션도 서블릿 스레드도 점유하지 않는다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CompletableFuture<MessageResponse> sendMessage(Long userId, Long storyId, MessageSendRequest request) {
        List<ChatMessage> prompt =
                messageTxService.appendUserMessageAndBuildPrompt(userId, storyId, request.getContent());

        return llmClient.generate(prompt)
                .thenApply(reply -> messageTxService.appendAssistantReply(storyId, reply));
    }

    public MessagePageResponse getMessages(Long userId, Long storyId, Long cursor, int size) {
        findOwned(storyId, userId);

        int limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest page = PageRequest.of(0, limit);
        Slice<Message> slice = (cursor == null)
                ? messageRepository.findByStoryIdOrderByIdDesc(storyId, page)
                : messageRepository.findByStoryIdAndIdLessThanOrderByIdDesc(storyId, cursor, page);

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
    public void deleteStory(Long userId, Long storyId) {
        Story story = findOwned(storyId, userId);
        messageRepository.deleteByStoryId(storyId);
        storyRepository.delete(story);
    }

    private Story findOwned(Long storyId, Long userId) {
        return storyRepository.findByIdAndUserId(storyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
    }
}
