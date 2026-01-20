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
import java.util.Map;
import java.util.stream.Collectors;
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

    // 조회 한 번에 내려줄 메시지 수 상한. 클라가 큰 size를 넘겨도 여기서 자른다.
    private static final int MAX_PAGE_SIZE = 100;

    // LLM 호출 실패 시 답을 빈 채로 두지 않고 대화체로 저장한다. 폴링이 이 메시지를 받고 정상 종료한다.
    private static final String LLM_FALLBACK =
            "지금은 답을 정리하기가 어렵네요. 잠시 후 다시 한 번 보내줄래요?";

    private final StoryRepository storyRepository;
    private final MessageRepository messageRepository;
    private final MessageTxService messageTxService;
    private final StoryFactExtractor factExtractor;
    private final LlmClient llmClient;
    private final UsageLimiter usageLimiter;

    @Transactional
    public StoryResponse create(Long userId, StoryCreateRequest request) {
        String title = (request.getTitle() == null || request.getTitle().isBlank())
                ? Story.DEFAULT_TITLE
                : request.getTitle().trim();

        Story story = storyRepository.save(Story.builder().userId(userId).title(title).build());

        return StoryResponse.from(story);
    }

    public List<StoryResponse> getStories(Long userId) {
        List<Story> stories = storyRepository.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(userId);
        if (stories.isEmpty()) {
            return List.of();
        }
        // 카톡식 목록 미리보기 — 사연별 마지막 메시지를 한 방 쿼리로 가져와 붙인다(N+1 회피).
        Map<Long, String> previews = messageRepository
                .findLatestPerStory(stories.stream().map(Story::getId).toList()).stream()
                .collect(Collectors.toMap(m -> m.getStory().getId(), m -> preview(m.getContent())));
        return stories.stream()
                .map(story -> StoryResponse.from(story, previews.get(story.getId())))
                .toList();
    }

    // 목록 한 줄용 — 개행은 공백으로 펴고 길면 자른다(전문은 방 안에서 보이므로 잘림은 무해).
    private static final int PREVIEW_LENGTH = 60;

    private String preview(String content) {
        String oneLine = content.replace('\n', ' ').strip();
        return oneLine.length() > PREVIEW_LENGTH ? oneLine.substring(0, PREVIEW_LENGTH) : oneLine;
    }

    // 폴링 방식: 유저 메시지를 저장하고 즉시 반환한다. 어시스턴트 답은 백그라운드에서 생성, 저장되고,
    // 클라이언트는 GET .../messages/since?after=<유저메시지id>로 폴링해 답이 붙는지 확인한다.
    // 트랜잭션 밖(NOT_SUPPORTED)에서 오케스트레이션 — 느린 LLM 호출이 DB 커넥션을 잡지 않게.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MessageResponse sendMessage(Long userId, Long storyId, MessageSendRequest request) {
        // 이 유저가 이미 대화 답변을 생성 중이면 접수를 거부한다(연타, 중복요청, 동시 발사로 한도 우회 차단).
        usageLimiter.acquireInFlight(UsageKind.CHAT, userId);
        try {
            // 후차감: 여기서는 한도 검사만 하고, 기록은 답변 저장이 성공한 뒤에 한다.
            // 유저가 폴링을 끊어도(중지) 서버는 끝까지 저장하므로 "기록 시점"은 반드시 도달한다.
            usageLimiter.checkDaily(UsageKind.CHAT, userId);

            MessageTxService.PreparedSend prepared =
                    messageTxService.appendUserMessageAndBuildPrompt(userId, storyId, request.getContent());

            // fire-and-forget: 응답을 기다리지 않는다. 완료되면 어시스턴트 메시지로 저장, 실패하면 폴백 저장.
            // handle로 LLM 단계 예외와 저장 단계 예외를 분리한다(저장 실패를 'LLM 실패'로 오인 기록하지 않게).
            llmClient.generate(prepared.prompt())
                    .handle((reply, ex) -> {
                        if (ex != null) {
                            log.error("LLM 응답 생성 실패 storyId={} userId={}", storyId, userId, ex);
                            persistFallbackQuietly(storyId);
                        } else {
                            persistReplyQuietly(userId, storyId, reply);
                        }
                        return null;
                    })
                    .whenComplete((ignored, ex) -> {
                        // handle에서 예외를 삼키므로 여기 ex는 보통 null이지만, 만일을 대비해 흔적을 남긴다(무로그 방지).
                        if (ex != null) {
                            log.error("메시지 처리 파이프라인 예상외 실패 storyId={} userId={}", storyId, userId, ex);
                        }
                        usageLimiter.releaseInFlight(UsageKind.CHAT, userId);
                    });

            return prepared.userMessage();
        } catch (RuntimeException e) {
            // 후차감이라 되돌릴 차감이 없다. 잠금만 풀고 그대로 던진다.
            usageLimiter.releaseInFlight(UsageKind.CHAT, userId);
            throw e;
        }
    }

    // LLM 성공 후: 답변 저장 → 차감 → 사실 추출. 저장 단계 실패는 'LLM 실패'와 구분해 명확히 남긴다.
    private void persistReplyQuietly(Long userId, Long storyId, String reply) {
        try {
            messageTxService.appendAssistantReply(storyId, reply);
        } catch (RuntimeException e) {
            // LLM은 성공했으나 답을 못 남긴 상태 — 폴링이 끝나지 않는 CS 원인이 되므로 반드시 추적 가능해야 한다.
            log.error("LLM 응답은 받았으나 답변 저장 실패 storyId={} userId={}", storyId, userId, e);
            return;
        }
        recordUsageQuietly(userId);          // 성공 시만 차감. 폴백(LLM 장애)은 유저 잘못이 아니라 미차감.
        factExtractor.extractAsync(storyId); // 원장 갱신. 실패해도 채팅에 영향 없음(내부에서 삼킴).
    }

    // LLM 실패 시 폴백 저장. 이마저 실패하면 답도 폴백도 없이 조용히 사라지므로 반드시 로그를 남긴다.
    private void persistFallbackQuietly(Long storyId) {
        try {
            messageTxService.appendAssistantReply(storyId, LLM_FALLBACK);
        } catch (RuntimeException e) {
            log.error("폴백 메시지 저장까지 실패 storyId={} — 유저 폴링이 종료되지 않는다", storyId, e);
        }
    }

    // 쿼터 기록 실패가 이미 저장된 답변을 실패 처리(폴백 중복 저장)로 오염시키지 않게 격리한다.
    private void recordUsageQuietly(Long userId) {
        try {
            usageLimiter.recordDaily(UsageKind.CHAT, userId);
        } catch (RuntimeException e) {
            log.error("대화 쿼터 기록 실패 userId={}", userId, e);
        }
    }

    // 폴링: 방금 보낸 메시지(afterId) 이후 새로 생긴 메시지(주로 어시스턴트 답)를 시간순으로 반환.
    @Transactional
    public List<MessageResponse> getMessagesSince(Long userId, Long storyId, Long afterId) {
        Story story = findOwned(storyId, userId);
        List<MessageResponse> fresh = messageRepository
                .findByStoryIdAndIdGreaterThanOrderByIdAsc(storyId, afterId).stream()
                .map(MessageResponse::from)
                .toList();
        // 답을 화면에서 받아봤으니 읽음 처리 — 목록 안읽음 배지의 기준 시각.
        if (!fresh.isEmpty()) {
            story.markRead();
        }
        return fresh;
    }

    @Transactional
    public MessagePageResponse getMessages(Long userId, Long storyId, Long cursor, int size) {
        Story story = findOwned(storyId, userId);
        // 방에 들어와 최신 페이지를 본 시점(cursor 없음)이 곧 읽음이다. 과거 페이징은 해당 없음.
        if (cursor == null) {
            story.markRead();
        }

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

    // 소프트 딜리트: 대화, 기억, 진단은 남길 기록이라 물리 삭제하지 않고 사연에 삭제 시각만 찍는다.
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
