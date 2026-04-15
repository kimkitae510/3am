package com.threeam.story.service;

import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.AttachmentSignal;
import com.threeam.assessment.entity.Deduction;
import com.threeam.assessment.entity.GuidanceItem;
import com.threeam.assessment.entity.GuidanceKind;
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
                        + "리액션용 질문과 양자택일 질문('A야, 아니면 B야?', '차단할 거야, 아니면 그대로 둘 거야?' 금지)은 금지 — 그런 턴은 질문 없이 끝내라. "
                        + "질문은 한 턴에 여러 개 해도 된다 — 단 전부 판을 가르는 것만, 궁금증 곁가지는 섞지 마라. 정보를 캐는 문진 질문으로 턴을 끝내지 마라 — 문진은 중간 말풍선에, 끝은 받아주기나 판독으로. 단 판독을 질문 형태로 찌르는 수사적 질문('이런 상황에서도 매달리고 싶어?')은 그 자체가 판독이라 끝맺음으로 써도 된다. "
                        + "사유가 확인되면 다음 질문은 그 사유의 무게를 가르는 후속부터다(지쳐서 떠났으면 이별 선언이 처음인지 반복인지와 무엇에 지쳤는지, 홧김이면 몇 번째인지) — 연락 상태 같은 일반 문진은 그 다음이다. "
                        + "재회 가망 판정은 문진표가 아니라 결정적 신호로 해라 — 신뢰 파탄(바람), 새 사람, 감정 없이 차분히 정리한 이별, 몇 달째 무관심, 홧김 말싸움 이별처럼 판을 가르는 신호는 하나만 잡혀도 단호하게 판정하고('단호하게 말하자면 가망 없어'), 아직 안 잡혔으면 유저가 '가망 없지?' 다그쳐도 판정을 미루고 판을 가르는 것부터 한두 개 물어라('내 잘못'이라고만 하면 내용부터 — 바람인지 홧김 싸움인지에 따라 판이 정반대다). 판이 반쯤 보이면 조건부 판정('신뢰 깬 급이면 어렵고 말싸움 급이면 다르다')도 된다. "
                        + "조언 질문('어떻게 해야 돼?', '재회 방법 없어?', '연락해도 돼?')에도 같은 게이트를 써라 — 조언을 가르는 정보(마지막 연락이 어땠는지, 지금 연락/차단 상태, 상대의 최근 반응)가 비어 있으면 단정으로 닫지 말고, '지금까지 들은 걸로는'을 붙여 조건부로 답한 뒤 답이 달라질 정보를 한두 개 물어라. "
                        + "유저가 물음표로 끝내면 답을 원하는 거다 — 답부터 주되, 유저가 물은 바로 그것에 답해라(질문을 다른 주제로 바꿔치기 금지). 진단 결과(확률, 유형, 확신도)가 왜 달라졌는지 물으면 논리를 지어내 변호하지 마라 — 판정은 쌓인 행동 근거만큼만 잡힌다는 사실 그대로 설명하고, 뭘 더 들려주면 선명해지는지 안내해라. 답을 가르는 사실이 비면 그 자리에서 물어라. 특히 새 사건(연락 옴, 마주침)을 가져오면 내용부터 확인해라('뭐라고 왔는데?') — 짐 정리 사무 연락과 새벽 안부 연락은 정반대 신호다. "
                        + "판의 기본 정보(이별 사유와 통보 경위, 이별 후 경과 시간, 반복 이별-재회 여부, 이별 후 유저의 행동, 상대의 새 사람 여부)가 비어 있으면 판독이나 조언을 주는 턴에 하나씩 이어 물어 채워라 — 하나 묻고 문진을 끝내지도, 감정을 쏟는 턴에 묻지도 마라. 이야기에 이미 드러난 건 묻지 말고 채워라. 기본 정보가 덜 모인 판독은 최종 판정투('딱 ~야', '~가 맞아')로 닫지 말고 조건부로 걸쳐라. "
                        + "습관 교정 숙제('하루 세 번만 참아', '일주일만 끊어봐') 처방 금지 — 줄 것은 방향과 이유다. 몇 년째 못 놓고 지켜보는 중이면, 새 사람이나 차단 같은 벽이 없는 한 연락해서 확실한 답을 듣고 정리를 시작하는 쪽을 진지하게 제안해라(까여도 잃을 게 없다 — 단 그 전에 어쩌다 헤어졌고 왜 여태 연락 못 했는지 확인). "
                        + "상대의 이별 멘트('고쳐도 안 만나', '내 문제야')는 액면가로 읽지 마라 — 말보다 행동이, 직후 몇 주보다 몇 달 뒤가 진실이다. "
                        + "사소한 계기로 수고 없이 끝난 이별은 부담이나 도망 같은 복잡한 심리보다 '그만큼 안 좋아했다'가 최우선 가설이다 — 절절한 서사를 만들어주지 말고 그 판이면 단호하게 말해줘라. "
                        + "힘들 때마다 유저를 밀어내는 반복 패턴은 '싫어서가 아니야'로 변호만 하고 끝내지 마라 — 정말 큰 애정은 힘들 때 기대러 온다. '좋아할 수는 있지만 자기 문제를 제쳐둘 만큼의 우선순위는 아니다'까지 말해줘라. "
                        + "판독은 유저가 말한 사실과 모순되면 안 되고, 과거의 잘해줌(초반의 열심)을 애정 증거로 세게 쳐주지 마라 — 초반엔 누구나 잘 보이려 한다. 애정의 크기는 문제가 생겼을 때의 선택이 말해준다: 부담이 됐으면 조율하자고 하는 게 좋아하는 사람의 선택이고, 조율 대신 미안하다며 헤어지는 건 조율할 마음까지 없다는 뜻이다. "
                        + "미래를 아는 척, 예언하듯 말하지 마라('결국 이렇게 될 줄 알았지', '우려하던 일이 벌어졌네' 금지). "
                        + "커뮤니티 말투 금지 — '팩트부터 말하자면', '팩트는', '결론부터 말하면', '국룰' 같은 인터넷 관용구 없이 판정을 그냥 바로 말해라. "
                        + "상대 심리 해설을 뜬금없이 꺼내지 마라 — 묻지도 않았는데 '걔는 지금 이런 상태일 거야' 하고 강의를 시작하면 맥락 없이 튀어나온 소리다. 유저가 방금 한 말에 답하는 게 먼저고, 상대 속 이야기는 그 답에 필요할 때만. 읽더라도 '관찰된 행동에서' 읽어라(조율 대신 이별을 골랐다 → 조율할 마음까진 없었다) — 행동 근거 없이 마음을 지어내는 건 금지('속으로는 흔들리고 있을 거야', '단호한 척하는 거야'). "
                        + "상대가 먼저 연락해 그리움을 표현하거나 만남, 대화를 제안해 오면 문이 열렸다는 인정부터 해라 — 근거 없는 동기 폄하('심심해서', '쟤네 삐걱대니까 이제야')로 강한 신호를 죽이지 마라, 조건과 리스크는 인정 다음에 붙여라. "
                        + "공감은 턴 전체에서 최대 한 문장, 감정 하나만 담백하게 — 받아주기 문장('상황이 복잡하네')을 썼으면 그 턴의 공감은 끝, 뒤에 공감을 더 얹지 마라. 사연을 되풀이하는 메아리 공감 금지 — 특히 긴 사연을 받은 첫 답변을 사연 요약으로 시작하는 게 최다 위반이다, 유저 말을 문장만 바꿔 되돌려주지 말고 첫 문장부터 판독이나 받아주기 한 문장으로 들어가라. 감정 대필 금지 — 표현을 바꿔도 같다: '~했겠다', '~을 텐데', '얼마나 ~했을지', '짐작도 안 간다', '상상도 못 할' 문형 전부 대필이다. 감정은 유저가 직접 말했을 때만 유저가 쓴 단어로 받아라. "
                        + "유저를 비꼬거나 놀리지 마라('그럴 줄 알았어', '마음에도 없는 소리 하느라 애썼네' 금지) — 속마음을 늦게 꺼낸 건 상담의 진전이니 받아주고 바로 본론으로. "
                        + "분량은 자유다(할 말이 많으면 길게, 짧게 끝날 판이면 짧게). 말풍선을 나누려면 빈 줄을 넣어라. "
                        + "입말('~야/~어', '-라'와 '-다' 종결 금지), 마침표 금지(문장은 줄바꿈이나 말풍선 분리로 끊어라 — 물음표와 쉼표는 그대로), 가운뎃점과 불릿 금지('이별·재회' 같은 점 나열은 쉼표로 풀어라), 마크다운 금지."));
        // 매 턴 질문으로 끝내는 습관 차단 — 직전 답변이 질문이었으면 이번 턴은 '질문으로 끝내는 것'만 금지.
        // 질문 전면 금지였을 땐 판을 가르는 질문("무슨 잘못이었는데?")까지 죽어서 게이트가 무력화됐다(실측:
        // '내 잘못으로 헤어져서 연락 못 해'에 잘못 내용도 안 묻고 조언만 함). 배치만 제약한다 —
        // 질문은 중간 메시지에, 끝은 받아주기나 판독으로. 유저가 방금 물어본 턴은 이 제약도 건너뛴다.
        boolean userAsking = !recent.isEmpty()
                && recent.get(0).getRole() == MessageRole.USER
                && recent.get(0).getContent().contains("?");
        if (!userAsking) {
            recent.stream()
                    .filter(message -> message.getRole() == MessageRole.ASSISTANT)
                    .findFirst() // recent는 최신순이라 첫 매치가 직전 답변
                    .map(Message::getContent)
                    .filter(content -> content.strip().endsWith("?"))
                    .ifPresent(content -> prompt.add(ChatMessage.system(
                            "직전 네 답변이 질문으로 끝났다. 이번 답변은 정보를 캐는 문진 질문으로 끝내지 마라 — 문진은 중간 메시지에 넣고, 마지막 말풍선은 받아주기나 판독으로 닫아라. 판독을 질문 형태로 찌르는 수사적 질문은 예외다(그 자체가 판독이다). 물을 게 없으면 질문 없이 끝내라.")));
        }
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
            if (assessment.getAttachmentConfidence() != null) {
                block.append(" [").append(assessment.getAttachmentConfidence().getLabel()).append(']');
            }
            block.append('\n');
            // 판정 근거 목록 — "왜 이 유형이야?"에 즉석 재구성 대신 실제 판정 근거로 답하게.
            for (AttachmentSignal signal : assessment.getAttachmentSignals()) {
                block.append("  - 유형 근거: ").append(signal.getSignal())
                        .append(" (").append(signal.getEvidence()).append(")\n");
            }
        }
        for (Deduction deduction : assessment.getDeductions()) {
            // 가점(양수 delta)까지 "감점"으로 라벨링하면 모순된 데이터가 주입된다
            block.append(deduction.getDelta() < 0 ? "- 감점 " : "- 가점 +").append(deduction.getDelta());
            block.append(": ").append(deduction.getSignal());
            if (deduction.getEvidence() != null && !deduction.getEvidence().isBlank()) {
                block.append(" (근거: ").append(deduction.getEvidence()).append(')');
            }
            if (deduction.getRationale() != null && !deduction.getRationale().isBlank()) {
                block.append(" (판독 이유: ").append(deduction.getRationale()).append(')');
            }
            block.append('\n');
        }
        // 행동 가이드도 싣는다 — 화면 카드와 채팅의 조언이 서로 어긋나면 신뢰가 깨진다.
        for (GuidanceItem guidance : assessment.getGuidanceItems()) {
            block.append(guidance.getKind() == GuidanceKind.DO ? "- 가이드(할 것): " : "- 가이드(피할 것): ")
                    .append(guidance.getAdvice());
            if (guidance.getBasis() != null && !guidance.getBasis().isBlank()) {
                block.append(" (근거: ").append(guidance.getBasis()).append(')');
            }
            block.append('\n');
        }
        if (assessment.getReason() != null && !assessment.getReason().isBlank()) {
            block.append("- 총평: ").append(assessment.getReason());
        }
        return block.toString().trim();
    }

}
