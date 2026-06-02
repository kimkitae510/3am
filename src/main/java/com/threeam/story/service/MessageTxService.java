package com.threeam.story.service;

import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.AssessmentFactor;
import com.threeam.assessment.entity.FactorLevel;
import com.threeam.assessment.repository.AssessmentRepository;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.llm.ChatMessage;
import com.threeam.llm.ChatPersonaProperties;
import com.threeam.story.dto.MessageResponse;
import com.threeam.story.entity.FactSource;
import com.threeam.story.entity.Message;
import com.threeam.story.entity.MessageRole;
import com.threeam.story.entity.Story;
import com.threeam.story.entity.StoryFact;
import com.threeam.story.entity.StoryMemory;
import com.threeam.story.repository.MessageRepository;
import com.threeam.story.repository.StoryFactRepository;
import com.threeam.story.repository.StoryMemoryRepository;
import com.threeam.story.repository.StoryRepository;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 메시지 전송의 DB 단계를 "짧은 트랜잭션"으로 분리한다.
// 느린 LLM 호출은 이 트랜잭션 밖(StoryService)에서 일어나므로 커넥션을 점유하지 않는다.
@Service
@RequiredArgsConstructor
public class MessageTxService {

    // LLM에 실어 보낼 직전 맥락의 크기(메시지 수). 토큰, 비용을 제한하기 위한 window.
    private static final int HISTORY_WINDOW = 20;

    // 채팅 프롬프트에 싣는 사실 원장 상한(최근 N개). 원장은 무제한으로 쌓이므로 통째로 실으면
    // 대화가 길수록 호출당 입력 토큰이 선형 증가한다. 채팅은 맥락용이라 진단(50)보다 적은 30.
    private static final int FACT_INJECT_LIMIT = 30;

    private static final DateTimeFormatter FACT_DATE = DateTimeFormatter.ofPattern("M/d");


    // 페르소나 실문구는 저장소 밖(persona.yml, gitignore)에서 주입된다. 코드에는 자리표시 기본값만 있다.
    private final ChatPersonaProperties personaProperties;
    private final AssessmentRepository assessmentRepository;
    private final StoryRepository storyRepository;
    private final MessageRepository messageRepository;
    private final StoryMemoryRepository storyMemoryRepository;
    private final StoryFactRepository storyFactRepository;

    // tx1: 소유권 확인 + 유저 메시지 저장 + LLM에 보낼 프롬프트 조립. 짧게 끝난다.
    // 폴링 전환 후: 저장한 유저 메시지(즉시 응답용)와 프롬프트(백그라운드 LLM용)를 함께 돌려준다.
    @Transactional
    public PreparedSend appendUserMessageAndBuildPrompt(Long userId, Long storyId, String content) {
        Story story = storyRepository.findByIdAndUserIdAndDeletedAtIsNull(storyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
        Message userMessage = messageRepository.save(Message.user(story, content));
        // 제목이 기본값이면 첫 메시지로 바꿔준다 — 목록이 "새 대화"만 줄지어 구분이 안 가는 문제.
        if (Story.DEFAULT_TITLE.equals(story.getTitle())) {
            story.rename(titleFrom(content));
        }
        return new PreparedSend(MessageResponse.from(userMessage), buildPrompt(storyId));
    }

    private String titleFrom(String content) {
        String oneLine = content.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= 20 ? oneLine : oneLine.substring(0, 20) + "…";
    }

    // 즉시 반환할 유저 메시지 + 백그라운드 LLM 호출에 쓸 프롬프트.
    public record PreparedSend(MessageResponse userMessage, List<ChatMessage> prompt) {}

    // tx1'(재시도): 폴백 말풍선을 걷어내고 같은 유저 메시지로 프롬프트를 다시 조립한다.
    // 유저에게 다시 타이핑을 시키지 않으려는 것이므로 유저 메시지는 새로 저장하지 않는다 —
    // 새로 저장하면 같은 말이 두 벌 남고, 사실 추출도 그 중복을 훑는다.
    // 폴백은 지운다: 유저 발화가 아니라 우리가 대신 낸 안내라 대화 기록으로 남길 값이 없고,
    // 남겨두면 재시도가 성공해도 실패 말풍선이 답 위에 그대로 붙어 있다.
    // 지우는 것은 잔여, 쿨다운 검사를 통과한 뒤다 — 먼저 지우면 거절당한 유저가 폴백 말풍선과
    // 재시도 버튼까지 잃고 같은 말을 다시 타이핑해야 한다.
    @Transactional
    public PreparedRetry prepareRetry(Long userId, Long storyId) {
        // 검사와 삭제 사이에 답이 붙거나 다른 요청이 먼저 지웠을 수 있어 여기서 한 번 더 본다.
        RetriableTurn turn = retriableTurn(userId, storyId);
        messageRepository.delete(turn.fallback());
        // 프롬프트 조립이 방금 지운 폴백을 다시 읽지 않게 먼저 밀어낸다.
        messageRepository.flush();
        Message userMessage = turn.userMessage();
        return new PreparedRetry(userMessage.getId(), userMessage.getContent(), buildPrompt(storyId));
    }

    // 마지막이 폴백이고 그 앞이 유저 메시지일 때만 재시도할 것이 있다.
    // (재시도를 두 번 눌렀거나 그새 정상 답이 붙었으면 여기서 걸린다)
    private RetriableTurn retriableTurn(Long userId, Long storyId) {
        storyRepository.findByIdAndUserIdAndDeletedAtIsNull(storyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
        List<Message> recent = messageRepository
                .findByStoryIdOrderByIdDesc(storyId, PageRequest.of(0, 2))
                .getContent();
        if (recent.size() < 2 || !recent.get(0).isFallback()
                || recent.get(1).getRole() != MessageRole.USER) {
            throw new BusinessException(ErrorCode.CHAT_RETRY_NOT_APPLICABLE);
        }
        return new RetriableTurn(recent.get(1), recent.get(0));
    }

    private record RetriableTurn(Message userMessage, Message fallback) {}

    // 폴링 기준 id(되살릴 답이 붙을 자리)와, 회수 환산에 쓸 원문, 그리고 프롬프트.
    public record PreparedRetry(Long pollAfterId, String userContent, List<ChatMessage> prompt) {}

    // tx2: LLM 응답을 어시스턴트 메시지로 저장 + 사연 활동시각 갱신.
    @Transactional
    public MessageResponse appendAssistantReply(Long storyId, String reply) {
        Story story = storyRepository.findById(storyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
        // 문장 끝 마침표는 코드가 걷어낸다 — 프롬프트 지시로는 확률적으로 새는 문체 규칙.
        Message answer = messageRepository.save(
                Message.assistant(story, com.threeam.global.text.Periods.strip(reply)));
        story.touch();
        return MessageResponse.from(answer);
    }

    private List<ChatMessage> buildPrompt(Long storyId) {
        // 방금 저장한 유저 메시지까지 포함해 최신순 N개를 가져온 뒤, 시간순으로 뒤집어 대화 순서를 복원한다.
        List<Message> recent = messageRepository
                .findByStoryIdOrderByIdDesc(storyId, PageRequest.of(0, HISTORY_WINDOW))
                .getContent();

        List<ChatMessage> prompt = new ArrayList<>();
        // 페르소나가 프롬프트의 맨 앞이자 유일한 고정 지시 블록이다. 캐싱은 앞에서부터 똑같은
        // 만큼만 먹으므로 고정분은 전부 여기 모으고, 매번 바뀌는 것(원장, 기억, 진단)은 뒤에 둔다.
        // 별도 리마인더 블록은 없앴다 — '프롬프트 말미에 다시 박는다'는 전제로 만들었는데
        // system 메시지는 언제나 대화보다 앞이라 말미인 적이 없었고, 결국 페르소나의 중복이었다.
        // 진짜 말미가 필요한 것은 출력 직전 점검뿐이고 그건 대화 뒤(contents)로 따로 나간다.
        prompt.add(ChatMessage.system(personaProperties.getPersona()));
        // 사실 원장: 창 밖으로 밀려나도 잊으면 안 되는 사건, 사실들. 괄호는 기록일.
        // 최근 N개만 최신순으로 가져와 시간순으로 뒤집는다(비용 상한).
        List<StoryFact> recentFacts = storyFactRepository.findByStoryIdOrderByIdDesc(
                storyId, PageRequest.of(0, FACT_INJECT_LIMIT));
        if (!recentFacts.isEmpty()) {
            StringBuilder block = new StringBuilder("기록된 사실(괄호는 기록일):");
            for (int i = recentFacts.size() - 1; i >= 0; i--) {
                StoryFact fact = recentFacts.get(i);
                block.append("\n- (").append(FACT_DATE.format(fact.getCreatedAt())).append(")");
                // 직접 입력분은 유저의 주장임을 표시한다(진단 프롬프트와 같은 라벨).
                if (fact.getSource() == FactSource.USER) {
                    block.append(" [유저 직접 입력]");
                }
                block.append(' ').append(fact.getFact());
            }
            prompt.add(ChatMessage.system(block.toString()));
        }
        // 창(window) 밖으로 밀려난 오래된 사실을 기억 요약으로 보충한다.
        storyMemoryRepository.findByStoryId(storyId)
                .map(StoryMemory::getSummary)
                .filter(summary -> !summary.isBlank())
                .ifPresent(summary -> prompt.add(ChatMessage.system("지금까지 요약: " + summary)));
        // 최근 진단을 매 턴 싣는다. 단 결과 라벨이 아니라 재료다 — 확률(강도의 기준점)과
        // 무겁게 본 사실들만 넣고, 유형 이름과 요인 이름과 점프 이름은 전부 뺀다.
        // 라벨을 실었을 때 그 단어가 그대로 대화에 새어나왔고("소진형 상태거든"), 결과만
        // 실었을 땐 새 사실이 나와도 낡은 값에 묶였다(둘 다 실측). 재료가 함께 있으면
        // 채팅이 그 위에서 다시 읽을 수 있어 새 신호를 스스로 반영한다.
        assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(storyId)
                .filter(a -> a.getProbability() != null)
                .ifPresent(a -> prompt.add(ChatMessage.system(describeAssessment(a))));
        for (int i = recent.size() - 1; i >= 0; i--) {
            Message message = recent.get(i);
            prompt.add(message.getRole() == MessageRole.USER
                    ? ChatMessage.user(message.getContent())
                    : ChatMessage.assistant(message.getContent()));
        }
        // 질문 원칙 리마인더 — 페르소나 중간의 질문 규칙이 비결정적으로 새는 실측 대응이라
        // 대화 뒤(안 묻히는 자리)에 싣는다. 단 첫 답변 턴에만이다: 매 턴 실었더니 "질문은 꼭
        // 해라"는 압박이 매 턴 재점화돼, 물을 게 없는 턴에도 차단 여부나 다짐 확인 같은 질문을
        // 짜냈다(실측). 이후 턴의 질문 규칙은 페르소나 본문이 맡는다.
        if (isFirstAnswer(recent)) {
            String questionReminder = personaProperties.getQuestionReminder();
            if (questionReminder != null && !questionReminder.isBlank()) {
                prompt.add(ChatMessage.system(questionReminder));
            }
        }
        // 출력 직전 점검은 반드시 대화 뒤, 프롬프트의 맨 끝이다 — 앞에 두면 리마인더와 같은
        // 자리가 되어 같은 이유로 묻힌다. 여기가 마지막으로 읽히는 지시라는 게 이 블록의 전부다.
        String finalCheck = personaProperties.getFinalCheck();
        if (finalCheck != null && !finalCheck.isBlank()) {
            prompt.add(ChatMessage.system(finalCheck));
        }
        return prompt;
    }


    // 이 턴이 이 사연의 첫 답변인지. 어시스턴트 메시지가 아직 없으면 첫 답변이다.
    // (recent는 방금 저장한 유저 메시지를 포함한 최신 N개다. 창 밖으로 밀려날 만큼 대화가
    // 길면 당연히 첫 답변이 아니므로 창 안만 봐도 충분하다.)
    // 진단을 재료로 옮긴 블록. 확률은 강도의 기준점으로만 주고, 나머지는 판정 라벨이 아니라
    // 관찰된 사실과 그 방향으로 적는다 — 유형, 점프, 요인 이름은 유저의 언어가 아니라 내부
    // 어휘라 프롬프트에 없으면 새어나갈 수도 없다. 중립(근거 없음) 슬롯은 판을 안 움직여 뺀다.
    private String describeAssessment(Assessment assessment) {
        StringBuilder block = new StringBuilder("최근 진단: 재회 가능성 ")
                .append(assessment.getProbability()).append("%\n");
        List<AssessmentFactor> weighted = assessment.getFactors().stream()
                .filter(f -> f.getLevel() != FactorLevel.NEUTRAL)
                .filter(f -> f.getEvidence() != null && !f.getEvidence().isBlank())
                .toList();
        if (!weighted.isEmpty()) {
            block.append("진단이 무겁게 본 것:\n");
            for (AssessmentFactor factor : weighted) {
                block.append("- ").append(factor.getEvidence().trim())
                        .append(" (").append(direction(factor.getLevel())).append(")\n");
            }
        }
        // 지시 문구는 프롬프트 자산이라 코드에 두지 않는다 — 코드는 데이터(확률, 사실, 방향)만
        // 만들고 사용 규칙은 로컬 yml(assessment-guide)에서 주입한다. 비면 데이터만 실린다.
        String guide = personaProperties.getAssessmentGuide();
        if (guide != null && !guide.isBlank()) {
            block.append(guide.trim());
        }
        return block.toString();
    }

    private String direction(FactorLevel level) {
        return switch (level) {
            case STRONG_FAVORABLE -> "크게 올림";
            case FAVORABLE -> "올림";
            case UNFAVORABLE -> "낮춤";
            case STRONG_UNFAVORABLE -> "크게 낮춤";
            default -> "";
        };
    }

    private boolean isFirstAnswer(List<Message> recent) {
        return recent.stream().noneMatch(message -> message.getRole() != MessageRole.USER);
    }


}
