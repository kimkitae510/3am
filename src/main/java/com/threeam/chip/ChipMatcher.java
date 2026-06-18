package com.threeam.chip;

import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.repository.AssessmentRepository;
import com.threeam.llm.ChatMessage;
import com.threeam.llm.LlmClient;
import com.threeam.story.entity.Message;
import com.threeam.story.entity.MessageRole;
import com.threeam.story.repository.MessageRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

// 자유입력이 40개 칩 중 어디에 해당하는지 가린다.
//
// 없으면 같은 질문인데 답의 깊이가 갈린다 — 칩을 누른 사람은 CONTACT 모듈로 전문 상담을 받고,
// 같은 말을 직접 타이핑한 사람은 일반 상담을 받는다. 유저는 왜 다른지 모른다.
//
// 저가 계층(generateJsonQuick)을 쓴다. 상담이 아니라 갈래 판정이라 강한 모델도 긴 추론도
// 값을 안 하는데, 상담과 같은 세기로 두면 판별 한 번이 상담 절반값이 된다(실측 40원).
//
// 못 가려도 상담은 그대로 돈다 — 실패하면 모듈 없이 평소 자유 상담이다.
@Slf4j
@Component
@RequiredArgsConstructor
public class ChipMatcher {

    // 몇 번째 답변부터 가리는가. 1~2회차는 질문과 핵심 분석이 회차 규칙으로 정해져 있어
    // 모듈이 낄 자리가 없다. 칩이 뜨는 시점과 같다.
    private static final int MATCH_FROM_ANSWER_NO = 2;

    // 판별에 싣는 대화. 이번 발화가 본체고 앞 한두 턴은 "그럼 언제요?" 같은 이어 말을 풀 맥락이다.
    private static final int HISTORY_WINDOW = 3;

    private final ChipStore chipStore;
    private final LlmClient llmClient;
    private final MessageRepository messageRepository;
    private final AssessmentRepository assessmentRepository;
    private final ChipMatchTxService chipMatchTxService;

    // 어떤 실패도 밖으로 던지지 않는다. 상담 호출이 이 뒤에 매달려 있어서, 여기서 터지면
    // 답변 자체가 안 나간다. 항상 완료되는 future를 돌려준다.
    public CompletableFuture<Void> matchAsync(Long storyId, Long userMessageId) {
        try {
            if (chipStore.matchPrompt().isBlank()
                    || messageRepository.countByStoryIdAndRole(storyId, MessageRole.ASSISTANT)
                            < MATCH_FROM_ANSWER_NO) {
                return CompletableFuture.completedFuture(null);
            }
            List<Message> recent = messageRepository
                    .findByStoryIdOrderByIdDesc(storyId, PageRequest.of(0, HISTORY_WINDOW))
                    .getContent();
            Integer probability = assessmentRepository
                    .findFirstByStoryIdOrderByCreatedAtDesc(storyId)
                    .map(Assessment::getProbability)
                    .orElse(null);

            return llmClient.generateJsonQuick(buildPrompt(recent, probability))
                    .thenAccept(json -> {
                        ChipDefinition chip = chipStore.matched(json, probability);
                        // 못 가린 것도 결과다 — 칩에 없는 질문이 무엇인지가 여기 남는다.
                        log.info("자유입력 판별 storyId={} messageId={} → {}",
                                storyId, userMessageId, chip == null ? "해당 없음" : chip.id());
                        if (chip != null) {
                            chipMatchTxService.saveQuietly(userMessageId, chip.id());
                        }
                    })
                    .exceptionally(ex -> {
                        log.warn("자유입력 판별 실패 storyId={} — 모듈 없이 상담한다", storyId, ex);
                        return null;
                    });
        } catch (RuntimeException e) {
            log.warn("자유입력 판별 준비 실패 storyId={}", storyId, e);
            return CompletableFuture.completedFuture(null);
        }
    }

    private List<ChatMessage> buildPrompt(List<Message> recent, Integer probability) {
        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(ChatMessage.system(chipStore.matchPrompt()));
        prompt.add(ChatMessage.system(chipStore.catalogBlock(probability)));
        for (int i = recent.size() - 1; i >= 0; i--) {
            Message message = recent.get(i);
            prompt.add(message.getRole() == MessageRole.USER
                    ? ChatMessage.user(message.getContent())
                    : ChatMessage.assistant(message.getContent()));
        }
        return prompt;
    }
}
