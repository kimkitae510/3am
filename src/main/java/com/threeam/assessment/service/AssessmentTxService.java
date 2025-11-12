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
import com.threeam.story.entity.StoryFact;
import com.threeam.story.entity.StoryMemory;
import com.threeam.story.repository.MessageRepository;
import com.threeam.story.repository.StoryFactRepository;
import com.threeam.story.repository.StoryMemoryRepository;
import com.threeam.story.repository.StoryRepository;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    // 사실 원장 상한. 넘치면 오래된 것부터 지운다(프롬프트 무한 성장 방지).
    private static final int MAX_FACTS = 50;

    private static final DateTimeFormatter FACT_DATE = DateTimeFormatter.ofPattern("M/d");

    private final StoryRepository storyRepository;
    private final MessageRepository messageRepository;
    private final StoryMemoryRepository storyMemoryRepository;
    private final StoryFactRepository storyFactRepository;
    private final AssessmentRepository assessmentRepository;

    // 히스토리 조회 전 소유권만 확인한다.
    @Transactional(readOnly = true)
    public void loadOwnership(Long userId, Long storyId) {
        storyRepository.findByIdAndUserIdAndDeletedAtIsNull(storyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
    }

    // tx1: 소유권 확인 + 최근 대화 + 기억 요약을 모아 온다. 짧게 끝난다.
    @Transactional(readOnly = true)
    public AssessmentContext loadContext(Long userId, Long storyId) {
        storyRepository.findByIdAndUserIdAndDeletedAtIsNull(storyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));

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
        if (newSummary != null && !newSummary.isBlank()) {
            upsertMemory(storyId, newSummary);
        }
        if (newFacts != null && !newFacts.isEmpty()) {
            appendFacts(storyId, saved.getId(), newFacts);
        }
        return AssessmentResponse.from(saved);
    }

    // 프롬프트용: "(11/10) 상대가 먼저 이별 통보" — 상대 시점 표현("일주일 전")을 기록일로 보정할 수 있게.
    private List<String> factLines(Long storyId) {
        return storyFactRepository.findByStoryIdOrderByIdAsc(storyId).stream()
                .map(fact -> "(" + FACT_DATE.format(fact.getCreatedAt()) + ") " + fact.getFact())
                .toList();
    }

    // 동일 문장은 건너뛰고(프롬프트의 중복 금지 지시가 1차, 여기가 2차 방어), 상한을 넘으면 오래된 것부터 지운다.
    private void appendFacts(Long storyId, Long assessmentId, List<String> newFacts) {
        List<StoryFact> existing = storyFactRepository.findByStoryIdOrderByIdAsc(storyId);
        Set<String> known = new HashSet<>();
        existing.forEach(fact -> known.add(normalize(fact.getFact())));

        List<StoryFact> toSave = new ArrayList<>();
        for (String fact : newFacts) {
            if (known.add(normalize(fact))) {
                toSave.add(StoryFact.of(storyId, fact, assessmentId));
            }
        }
        storyFactRepository.saveAll(toSave);

        int overflow = existing.size() + toSave.size() - MAX_FACTS;
        if (overflow > 0) {
            storyFactRepository.deleteAll(existing.subList(0, overflow));
        }
    }

    private String normalize(String fact) {
        return fact.replaceAll("\\s+", " ").trim();
    }

    private void upsertMemory(Long storyId, String summary) {
        storyMemoryRepository.findByStoryId(storyId)
                .ifPresentOrElse(
                        memory -> memory.updateSummary(summary),
                        () -> storyMemoryRepository.save(
                                StoryMemory.builder().storyId(storyId).summary(summary).build()));
    }
}
