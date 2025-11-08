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
import com.threeam.usage.UsageKind;
import com.threeam.usage.UsageLimiter;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoryService {

    private static final String DEFAULT_TITLE = "새 대화";

    // 조회 한 번에 내려줄 메시지 수 상한. 클라가 큰 size를 넘겨도 여기서 자른다.
    private static final int MAX_PAGE_SIZE = 100;

    // LLM 호출 실패 시 답을 빈 채로 두지 않고 대화체로 저장한다. 폴링이 이 메시지를 받고 정상 종료한다.
    private static final String LLM_FALLBACK =
            "지금은 답을 정리하기가 어렵네요. 잠시 후 다시 한 번 보내줄래요?";

    private final StoryRepository storyRepository;
    private final MessageRepository messageRepository;
    private final MessageTxService messageTxService;
    private final LlmClient llmClient;
    private final UsageLimiter usageLimiter;

    @Transactional
    public StoryResponse create(Long userId, StoryCreateRequest request) {
        String title = (request.getTitle() == null || request.getTitle().isBlank())
                ? DEFAULT_TITLE
                : request.getTitle().trim();

        Story story = storyRepository.save(Story.builder().userId(userId).title(title).build());

        return StoryResponse.from(story);
    }

    public List<StoryResponse> getStories(Long userId) {
        return storyRepository.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(userId).stream()
                .map(StoryResponse::from)
                .toList();
    }

    // 폴링 방식: 유저 메시지를 저장하고 즉시 반환한다. 어시스턴트 답은 백그라운드에서 생성·저장되고,
    // 클라이언트는 GET .../messages/since?after=<유저메시지id>로 폴링해 답이 붙는지 확인한다.
    // 트랜잭션 밖(NOT_SUPPORTED)에서 오케스트레이션 — 느린 LLM 호출이 DB 커넥션을 잡지 않게.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MessageResponse sendMessage(Long userId, Long storyId, MessageSendRequest request) {
        // 이 사연에서 답변 생성이 진행 중이면 접수 자체를 거부한다(연타·중복요청 차단).
        usageLimiter.acquireInFlight(UsageKind.CHAT, storyId);
        try {
            usageLimiter.consumeDaily(UsageKind.CHAT, userId);
        } catch (RuntimeException e) {
            usageLimiter.releaseInFlight(UsageKind.CHAT, storyId);
            throw e;
        }

        try {
            MessageTxService.PreparedSend prepared =
                    messageTxService.appendUserMessageAndBuildPrompt(userId, storyId, request.getContent());

            // fire-and-forget: 응답을 기다리지 않는다. 완료되면 어시스턴트 메시지로 저장, 실패하면 폴백 저장.
            llmClient.generate(prepared.prompt())
                    .thenAccept(reply -> messageTxService.appendAssistantReply(storyId, reply))
                    .exceptionally(ex -> {
                        log.error("LLM 응답 생성 실패 storyId={}", storyId, ex);
                        messageTxService.appendAssistantReply(storyId, LLM_FALLBACK);
                        return null;
                    })
                    .whenComplete((ignored, ex) -> usageLimiter.releaseInFlight(UsageKind.CHAT, storyId));

            return prepared.userMessage();
        } catch (RuntimeException e) {
            // LLM 비용이 나가기 전에 실패(소유권 없음, 요청 조립 실패 등) → 차감을 되돌리고 잠금 해제.
            usageLimiter.refundDaily(UsageKind.CHAT, userId);
            usageLimiter.releaseInFlight(UsageKind.CHAT, storyId);
            throw e;
        }
    }

    // 폴링: 방금 보낸 메시지(afterId) 이후 새로 생긴 메시지(주로 어시스턴트 답)를 시간순으로 반환.
    public List<MessageResponse> getMessagesSince(Long userId, Long storyId, Long afterId) {
        findOwned(storyId, userId);
        return messageRepository.findByStoryIdAndIdGreaterThanOrderByIdAsc(storyId, afterId).stream()
                .map(MessageResponse::from)
                .toList();
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

    // 소프트 딜리트: 대화·기억·진단은 남길 기록이라 물리 삭제하지 않고 사연에 삭제 시각만 찍는다.
    @Transactional
    public void deleteStory(Long userId, Long storyId) {
        Story story = findOwned(storyId, userId);
        story.softDelete();
    }

    private Story findOwned(Long storyId, Long userId) {
        return storyRepository.findByIdAndUserIdAndDeletedAtIsNull(storyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
    }
}
