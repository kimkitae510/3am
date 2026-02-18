package com.threeam.story.service;

import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.Deduction;
import com.threeam.assessment.entity.ReunionVerdict;
import com.threeam.assessment.repository.AssessmentRepository;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.llm.ChatMessage;
import com.threeam.llm.ChatPersonaProperties;
import com.threeam.story.dto.MessageResponse;
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

    // tx2: LLM 응답을 어시스턴트 메시지로 저장 + 사연 활동시각 갱신.
    @Transactional
    public MessageResponse appendAssistantReply(Long storyId, String reply) {
        Story story = storyRepository.findById(storyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
        Message answer = messageRepository.save(Message.assistant(story, reply));
        story.touch();
        return MessageResponse.from(answer);
    }

    private List<ChatMessage> buildPrompt(Long storyId) {
        // 방금 저장한 유저 메시지까지 포함해 최신순 N개를 가져온 뒤, 시간순으로 뒤집어 대화 순서를 복원한다.
        List<Message> recent = messageRepository
                .findByStoryIdOrderByIdDesc(storyId, PageRequest.of(0, HISTORY_WINDOW))
                .getContent();

        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(ChatMessage.system(personaProperties.getPersona()));
        // 사실 원장: 창 밖으로 밀려나도 잊으면 안 되는 사건, 사실들. 괄호는 기록일.
        // 최근 N개만 최신순으로 가져와 시간순으로 뒤집는다(비용 상한).
        List<StoryFact> recentFacts = storyFactRepository.findByStoryIdOrderByIdDesc(
                storyId, PageRequest.of(0, FACT_INJECT_LIMIT));
        if (!recentFacts.isEmpty()) {
            StringBuilder block = new StringBuilder("기록된 사실(괄호는 기록일):");
            for (int i = recentFacts.size() - 1; i >= 0; i--) {
                StoryFact fact = recentFacts.get(i);
                block.append("\n- (").append(FACT_DATE.format(fact.getCreatedAt())).append(") ")
                        .append(fact.getFact());
            }
            prompt.add(ChatMessage.system(block.toString()));
        }
        // 창(window) 밖으로 밀려난 오래된 사실을 기억 요약으로 보충한다.
        storyMemoryRepository.findByStoryId(storyId)
                .map(StoryMemory::getSummary)
                .filter(summary -> !summary.isBlank())
                .ifPresent(summary -> prompt.add(ChatMessage.system("지금까지 요약: " + summary)));
        // 최신 진단을 실어, 유저가 "왜 이 진단이야?" 같은 후속 질문을 하면 근거를 들어 설명할 수 있게 한다.
        assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(storyId)
                .ifPresent(assessment -> prompt.add(ChatMessage.system(describeAssessment(assessment))));
        // 페르소나 깊숙한 규칙은 자주 무시된다(실측) — 제일 잘 어기는 것만 프롬프트 말미에 매 턴 다시 박는다.
        prompt.add(ChatMessage.system(
                "스타일 리마인더: 질문은 그 답을 들어야 네 다음 말이 달라질 때만, 이별과 재회와 상대와의 관계에 관한 것만 해라. "
                        + "유저 신상(치료, 병원, 직장, 가족)은 걱정돼도 묻지 마라. "
                        + "리액션용 질문과 양자택일 질문('A야, 아니면 B야?')은 금지 — 그런 턴은 질문 없이 끝내라. "
                        + "재회 가망 판정은 문진표가 아니라 결정적 신호로 해라 — 신뢰 파탄(바람), 새 사람, 감정 없이 차분히 정리한 이별, 몇 달째 무관심, 홧김 말싸움 이별처럼 판을 가르는 신호는 하나만 잡혀도 단호하게 판정하고('단호하게 말하자면 가망 없어'), 아직 안 잡혔으면 유저가 '가망 없지?' 다그쳐도 판정을 미루고 판을 가르는 것부터 한두 개 물어라('내 잘못'이라고만 하면 내용부터 — 바람인지 홧김 싸움인지에 따라 판이 정반대다). 판이 반쯤 보이면 조건부 판정('신뢰 깬 급이면 어렵고 말싸움 급이면 다르다')도 된다. "
                        + "상대의 이별 멘트('고쳐도 안 만나', '내 문제야')는 액면가로 읽지 마라 — 말보다 행동이, 직후 몇 주보다 몇 달 뒤가 진실이다. "
                        + "미래를 아는 척, 예언하듯 말하지 마라('결국 이렇게 될 줄 알았지', '우려하던 일이 벌어졌네' 금지). "
                        + "공감은 한 문장, 감정 하나만 담백하게 — '정말 너무 아쉽고 마음 아프다', '감히 상상도 안 된다', 감정 여러 개 나열 금지. "
                        + "답변은 2~5문장을 카톡 치듯 메시지 1~3개로 끊어 보내라(메시지 사이 빈 줄 한 개, 한 메시지는 한 호흡). "
                        + "입말('~야/~어', '-라'와 '-다' 종결 금지), 마크다운 금지."));
        // 매 턴 질문으로 끝내는 습관 차단 — 직전 답변이 질문이었으면 이번 턴은 무조건 질문 금지.
        recent.stream()
                .filter(message -> message.getRole() == MessageRole.ASSISTANT)
                .findFirst() // recent는 최신순이라 첫 매치가 직전 답변
                .map(Message::getContent)
                .filter(content -> content.strip().endsWith("?"))
                .ifPresent(content -> prompt.add(ChatMessage.system(
                        "직전 네 답변이 질문으로 끝났다. 이번 답변에는 질문을 넣지 마라 — 물음표 없이, 말을 받아주는 것으로 끝내라.")));
        for (int i = recent.size() - 1; i >= 0; i--) {
            Message message = recent.get(i);
            prompt.add(message.getRole() == MessageRole.USER
                    ? ChatMessage.user(message.getContent())
                    : ChatMessage.assistant(message.getContent()));
        }
        return prompt;
    }

    // 진단 결과를 설명용 데이터 블록으로 만든다. 재계산, 창작, 그리고 "묻지 않은 확률 들이대기"를 막는 지시를 함께 싣는다.
    private String describeAssessment(Assessment assessment) {
        StringBuilder block = new StringBuilder(
                "최근 재회 진단 결과 데이터(사용 규칙: "
                        + "유저가 이 진단의 이유나 점수를 '직접' 물을 때만 이 데이터를 근거로 설명하라. "
                        + "유저가 상대 행동의 의미나 가능성을 일반적으로 물으면(예: 이거 재회 신호 아니야?) "
                        + "이 확률 숫자를 꺼내지 말고 대화로만 답하라 — 묻지 않은 확률을 먼저 말하는 건 금지다. "
                        + "진단 일시를 그대로 낭독하지 말고, 진단이 대화로 자동 갱신되지 않는다는 걸 전할 필요가 "
                        + "있을 때만 '지난번 진단 기준'처럼 자연스럽게 짚어라. "
                        + "확률을 다시 계산하거나 여기 없는 진단 내용을 지어내지 마라):\n");
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
        if (assessment.getPartnerAttachment() != null) {
            block.append("- 상대 애착유형: ").append(assessment.getPartnerAttachment().getLabel());
            appendAttachmentEvidence(block, assessment.getPartnerAttachmentEvidence());
        }
        for (Deduction deduction : assessment.getDeductions()) {
            // 가점(양수 delta)까지 "감점"으로 라벨링하면 모순된 데이터가 주입된다
            block.append(deduction.getDelta() < 0 ? "- 감점 " : "- 가점 +").append(deduction.getDelta());
            block.append(": ").append(deduction.getSignal());
            if (deduction.getEvidence() != null && !deduction.getEvidence().isBlank()) {
                block.append(" (근거: ").append(deduction.getEvidence()).append(')');
            }
            block.append('\n');
        }
        if (assessment.getReason() != null && !assessment.getReason().isBlank()) {
            block.append("- 총평: ").append(assessment.getReason());
        }
        return block.toString().trim();
    }

    // 유형 근거가 저장돼 있으면 라벨 옆에 붙인다 — "왜 이 유형이야?"에 즉석 재구성 대신 실제 판정 근거로 답하게.
    private void appendAttachmentEvidence(StringBuilder block, String evidence) {
        if (evidence != null && !evidence.isBlank()) {
            block.append(" (판정 근거: ").append(evidence).append(')');
        }
        block.append('\n');
    }
}
