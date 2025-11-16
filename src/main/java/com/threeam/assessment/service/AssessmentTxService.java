package com.threeam.assessment.service;

import com.threeam.assessment.dto.AssessmentContext;
import com.threeam.assessment.dto.AssessmentResponse;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.repository.AssessmentRepository;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.llm.ChatMessage;
import com.threeam.story.entity.Message;
import com.threeam.story.entity.MessageRole;
import com.threeam.story.entity.StoryMemory;
import com.threeam.story.repository.MessageRepository;
import com.threeam.story.repository.StoryFactRepository;
import com.threeam.story.repository.StoryMemoryRepository;
import com.threeam.story.repository.StoryRepository;
import com.threeam.story.service.StoryFactService;
import com.threeam.story.service.StoryMemoryService;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 진단의 DB 단계를 "짧은 트랜잭션"으로 분리한다.
// 느린 LLM 호출은 이 트랜잭션 밖(AssessmentService)에서 일어나므로 커넥션을 점유하지 않는다.
@Service
@RequiredArgsConstructor
public class AssessmentTxService {

    private static final int HISTORY_WINDOW = 20;

    private static final DateTimeFormatter FACT_DATE = DateTimeFormatter.ofPattern("M/d");

    private final StoryRepository storyRepository;
    private final MessageRepository messageRepository;
    private final StoryMemoryRepository storyMemoryRepository;
    private final StoryMemoryService storyMemoryService;
    private final StoryFactRepository storyFactRepository;
    private final StoryFactService storyFactService;
    private final AssessmentRepository assessmentRepository;

    // 히스토리 조회 전 소유권만 확인한다.
    @Transactional(readOnly = true)
    public void loadOwnership(Long userId, Long storyId) {
        storyRepository.findByIdAndUserIdAndDeletedAtIsNull(storyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
    }

    // tx1: 소유권 확인 + 재진단 가드 + 최근 대화 + 기억 요약을 모아 온다. 짧게 끝난다.
    @Transactional(readOnly = true)
    public AssessmentContext loadContext(Long userId, Long storyId) {
        storyRepository.findByIdAndUserIdAndDeletedAtIsNull(storyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));

        // 재진단 가드: 확률을 바꾸는 건 수다의 양이 아니라 '사건'이다. 같은 근거로 다시 진단해
        // 확률이 출렁이는 것을 막고, LLM 쿼터도 아낀다. 첫 진단(기록 없음)은 그대로 통과.
        assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(storyId)
                .ifPresent(last -> assertNewBasisSince(storyId, last));

        List<Message> recent = messageRepository
                .findByStoryIdOrderByIdDesc(storyId, PageRequest.of(0, HISTORY_WINDOW))
                .getContent();
        if (recent.isEmpty()) {
            throw new BusinessException(ErrorCode.ASSESSMENT_NO_MESSAGES);
        }

        // 최신→과거로 왔으니 시간순으로 뒤집어 대화 순서를 복원한다.
        List<ChatMessage> conversation = new ArrayList<>();
        for (int i = recent.size() - 1; i >= 0; i--) {
            Message message = recent.get(i);
            conversation.add(message.getRole() == MessageRole.USER
                    ? ChatMessage.user(message.getContent())
                    : ChatMessage.assistant(message.getContent()));
        }

        String summary = storyMemoryRepository.findByStoryId(storyId)
                .map(StoryMemory::getSummary)
                .orElse(null);

        return new AssessmentContext(summary, factLines(storyId), conversation);
    }

    // tx2: 진단 결과 저장 + 기억(감정 요약) 갱신 + 새 사실 원장 append.
    @Transactional
    public AssessmentResponse save(Long storyId, Assessment assessment, String newSummary,
                                   List<String> newFacts) {
        Assessment saved = assessmentRepository.save(assessment);
        storyMemoryService.upsert(storyId, newSummary);
        storyFactService.appendFacts(storyId, saved.getId(), newFacts);
        return AssessmentResponse.from(saved);
    }

    // 새 대화가 없으면 진단 근거 자체가 없고, 대화가 있어도 원장에 새 사실이 없으면
    // "확률을 바꿀 사건"이 없다는 뜻이라 거부한다(채팅 추출이 대화에서 사실을 실시간으로 적재하는 전제).
    private void assertNewBasisSince(Long storyId, Assessment last) {
        if (!messageRepository.existsByStoryIdAndCreatedAtAfter(storyId, last.getCreatedAt())) {
            throw new BusinessException(ErrorCode.ASSESSMENT_NO_NEW_MESSAGES);
        }
        if (!storyFactRepository.existsNewFactSince(storyId, last.getCreatedAt(), last.getId())) {
            throw new BusinessException(ErrorCode.ASSESSMENT_NO_NEW_FACTS);
        }
    }

    // 프롬프트용: "(11/10) 상대가 먼저 이별 통보" — 상대 시점 표현("일주일 전")을 기록일로 보정할 수 있게.
    private List<String> factLines(Long storyId) {
        return storyFactRepository.findByStoryIdOrderByIdAsc(storyId).stream()
                .map(fact -> "(" + FACT_DATE.format(fact.getCreatedAt()) + ") " + fact.getFact())
                .toList();
    }

}
