package com.threeam.story.service;

import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.AssessmentFactor;
import com.threeam.assessment.entity.FactorLevel;
import com.threeam.assessment.entity.ReunionVerdict;
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

    // 진단 설명용: 유저가 "계속 대화하면 진단도 갱신된다"고 오해하기 쉬워, 이 결과가 언제 것인지 말하게 한다.
    private static final DateTimeFormatter ASSESSED_AT = DateTimeFormatter.ofPattern("M월 d일 HH:mm");

    // 페르소나 실문구는 저장소 밖(persona.yml, gitignore)에서 주입된다. 코드에는 자리표시 기본값만 있다.
    private final ChatPersonaProperties personaProperties;
    private final StoryRepository storyRepository;
    private final MessageRepository messageRepository;
    private final StoryMemoryRepository storyMemoryRepository;
    private final StoryFactRepository storyFactRepository;
    private final AssessmentRepository assessmentRepository;

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
        // 최신 진단: 상세 블록(요인, 판독 이유)은 유저가 진단을 화제로 꺼낸 턴에만 싣는다(비용).
        java.util.Optional<Assessment> latestAssessment =
                assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(storyId);
        if (needsAssessment(recent)) {
            latestAssessment.ifPresent(a -> prompt.add(ChatMessage.system(describeAssessment(a))));
        }
        for (int i = recent.size() - 1; i >= 0; i--) {
            Message message = recent.get(i);
            prompt.add(message.getRole() == MessageRole.USER
                    ? ChatMessage.user(message.getContent())
                    : ChatMessage.assistant(message.getContent()));
        }
        // 확률+유형 한 줄짜리 미니 라인은 매 턴, 그리고 '대화 뒤'에 싣는다 — 큐워드에 안 걸리는
        // 표현에서 페르소나가 진단과 다른 방향을 말한 사고 대응인데, 대화 앞에 실었더니
        // 페르소나 본문에 묻혀 그대로 재발했다(리마인더가 묻히던 것과 같은 자리 문제).
        if (!needsAssessment(recent)) {
            latestAssessment.ifPresent(a -> prompt.add(ChatMessage.system(assessmentMiniLine(a))));
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
    private boolean isFirstAnswer(List<Message> recent) {
        return recent.stream().noneMatch(message -> message.getRole() != MessageRole.USER);
    }

    // 진단 데이터를 실어야 하는 턴인지. 유저가 진단을 화제로 꺼냈을 때만 필요하다.
    // 넉넉하게 잡는다 — 안 실어서 "왜 이 확률이야?"에 답을 못 하는 쪽이, 몇 번 더 싣는 것보다 나쁘다.
    private static final List<String> ASSESSMENT_CUES = List.of(
            "진단", "확률", "퍼센트", "%", "가능성", "점수", "감점", "가점",
            // v2 개편으로 유저 어휘가 바뀌는 것 + "우리 다시 될까" 같은 확률 질문의 흔한 형태 보강.
            // 안 실어서 페르소나가 진단과 다른 방향을 말하는 쪽이, 몇 번 더 싣는 것보다 나쁘다.
            "될까", "승산", "유리", "불리", "요인", "유형");

    private boolean needsAssessment(List<Message> recent) {
        if (recent.isEmpty()) {
            return false;
        }
        String latest = recent.get(0).getContent();
        return latest != null && ASSESSMENT_CUES.stream().anyMatch(latest::contains);
    }

    // 매 턴 실리는 한 줄 요지. 코드는 데이터(확률, 유형)만 만들고 지시 문구는 프롬프트 자산이라
    // 로컬 yml(assessment-mini-guide)에서 주입한다 — 상세 재료는 큐워드 턴의 상세 블록이 답한다.
    private String assessmentMiniLine(Assessment assessment) {
        // 라벨을 상세 블록과 같은 접두어로 — 페르소나의 정렬 규칙("최근 재회 진단 결과 데이터가
        // 실려 있으면 맞춰라")이 문자열로 이 블록을 가리키므로, 라벨이 다르면 규칙이 안 물린다(실측:
        // 요지 라벨을 못 알아보고 큐워드 턴에만 정렬돼 턴마다 방향이 뒤집힘).
        StringBuilder line = new StringBuilder("최근 재회 진단 결과 데이터(요지): ");
        if (assessment.getProbability() != null) {
            line.append("확률 ").append(assessment.getProbability()).append('%');
        } else {
            line.append("판정 ").append(assessment.getVerdict());
        }
        if (assessment.getBreakupType() != null) {
            line.append(", 유형 ").append(assessment.getBreakupType().label());
        }
        String guide = personaProperties.getAssessmentMiniGuide();
        if (guide != null && !guide.isBlank()) {
            line.append(" — ").append(guide.trim());
        }
        return line.toString();
    }

    // 진단 결과를 설명용 데이터 블록으로 만든다. 재계산, 창작, 그리고 "묻지 않은 확률 들이대기"를 막는 지시를 함께 싣는다.
    private String describeAssessment(Assessment assessment) {
        StringBuilder block = new StringBuilder(
                "최근 재회 진단 결과 데이터(사용 규칙: 유저가 이 진단의 이유나 확률을 직접 물을 때만 "
                        + "이 데이터를 근거로 설명하라. 묻지 않은 확률을 먼저 꺼내지 마라. 진단은 대화로 "
                        + "자동 갱신되지 않는다 — 필요할 때만 '지난번 진단 기준'처럼 자연스럽게 짚어라. "
                        + "여기 없는 내용을 지어내거나 확률을 다시 계산하지 마라):\n");
        block.append("- 진단 일시: ").append(ASSESSED_AT.format(assessment.getCreatedAt())).append('\n');
        if (assessment.getVerdict() == ReunionVerdict.DATING) {
            block.append("- 판정: 아직 사귀는 중 — 재회 확률은 이별 전제라 산출하지 않음. "
                    + "확률을 물으면 이 이유를 설명하라(숫자를 지어내지 마라)\n");
        }
        if (assessment.getVerdict() == ReunionVerdict.REUNITED) {
            block.append("- 판정: 재회 성공, 다시 만나는 중 — 확률 산출 없음. "
                    + "이제 관계를 잘 이어가는 쪽을 도와라(숫자를 지어내지 마라)\n");
        }
        if (assessment.getProbability() != null) {
            block.append("- 재회 가능성: ").append(assessment.getProbability()).append("%\n");
        }
        if (assessment.getBreakupType() != null) {
            block.append("- 이별 유형: ").append(assessment.getBreakupType().label()).append('\n');
        }
        // 요인은 판정과 판독 이유만 싣는다. 근거(evidence)는 원장과 겹치고, 총평은 요약과 화면에
        // 이미 있다. 판독 이유는 이 블록에만 있는 정보라 유지 — 없으면 "왜 이 판정이야?"에
        // 페르소나가 이유를 지어내 화면 카드와 다른 말을 하게 된다. 중립(근거 없음)은 정보가
        // 아니라 잡음이라 뺀다.
        for (AssessmentFactor factor : assessment.getFactors()) {
            if (factor.getLevel() == FactorLevel.NEUTRAL) {
                continue;
            }
            block.append(factor.getLevel().favorableSide() ? "- 유리 요인(" : "- 불리 요인(")
                    .append(factor.getName().label()).append(", ")
                    .append(factor.getLevel().label()).append(")");
            if (factor.getRationale() != null && !factor.getRationale().isBlank()) {
                block.append(": ").append(factor.getRationale());
            }
            block.append('\n');
        }
        if (assessment.getRelapseRisk() != null) {
            block.append("- 재회 후 같은 문제 반복 위험: ").append(assessment.getRelapseRisk().label());
            if (assessment.getRelapseReason() != null && !assessment.getRelapseReason().isBlank()) {
                block.append(" — ").append(assessment.getRelapseReason());
            }
            block.append('\n');
        }
        return block.toString().trim();
    }

}
