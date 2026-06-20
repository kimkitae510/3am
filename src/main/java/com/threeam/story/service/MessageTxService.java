package com.threeam.story.service;

import com.threeam.assessment.dto.RelationshipPsychology;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.AssessmentFactor;
import com.threeam.assessment.entity.FactorLevel;
import com.threeam.assessment.entity.WatchPoint;
import com.threeam.assessment.repository.AssessmentRepository;
import com.threeam.chip.ChipDefinition;
import com.threeam.chip.ChipStore;
import com.threeam.chip.DiagnosisContext;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.llm.ChatMessage;
import com.threeam.llm.ChatPersonaProperties;
import com.threeam.story.dto.MessageResponse;
import com.threeam.chip.SuggestedChip;
import com.threeam.story.entity.ChatMeta;
import com.threeam.story.entity.ChipTail;
import com.threeam.story.entity.FactSource;
import com.threeam.story.entity.Message;
import com.threeam.story.entity.MessageRole;
import com.threeam.story.entity.Story;
import com.threeam.story.entity.StoryFact;
import com.threeam.story.entity.StoryMemory;
import com.threeam.story.repository.MessageRepository;
import com.threeam.story.repository.StoryFactRepository;
import com.threeam.story.repository.StoryIntakeRepository;
import com.threeam.story.repository.StoryMemoryRepository;
import com.threeam.story.repository.StoryRepository;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 메시지 전송의 DB 단계를 "짧은 트랜잭션"으로 분리한다.
// 느린 LLM 호출은 이 트랜잭션 밖(StoryService)에서 일어나므로 커넥션을 점유하지 않는다.
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageTxService {

    // LLM에 실어 보낼 직전 맥락의 크기(메시지 수). 토큰, 비용을 제한하기 위한 window.
    private static final int HISTORY_WINDOW = 20;

    // 채팅 프롬프트에 싣는 사실 원장 상한(최근 N개). 원장은 무제한으로 쌓이므로 통째로 실으면
    // 대화가 길수록 호출당 입력 토큰이 선형 증가한다. 채팅은 맥락용이라 진단(50)보다 적은 30.
    private static final int FACT_INJECT_LIMIT = 30;

    // 몇 번째 답변부터 칩을 뽑는가. 1회차 자리는 질문 카드가 쓴다.
    private static final int CHIP_FROM_ANSWER_NO = 2;

    private static final int SUGGEST_COUNT = 3;

    // 재작성 라벨 길이 상한. 칩은 한 줄짜리 UI라 넘치면 접힌다.
    private static final int MAX_LABEL_LENGTH = 30;

    private static final DateTimeFormatter FACT_DATE = DateTimeFormatter.ofPattern("M/d");

    // 마크다운 강조(**), 제목(#) 기호. 결론 강조용으로 굵기를 허용해봤다가 걷었다(굵기 없이
    // 문장 분리로 충분했다) — 프롬프트만 빼면 모델이 흘린 **가 무작위로 렌더링되므로 저장 전에
    // 걷는다. 기록에 남으면 다음 턴 프롬프트에 실려 모델이 같은 형식을 이어가는 문제도 그대로다.
    private static final Pattern MARKDOWN_MARKS =
            Pattern.compile("\\*\\*|^#{1,6}\\s+", Pattern.MULTILINE);

    static String stripMarkdown(String reply) {
        return reply == null ? null : MARKDOWN_MARKS.matcher(reply).replaceAll("");
    }


    // 페르소나 실문구는 저장소 밖(persona.yml, gitignore)에서 주입된다. 코드에는 자리표시 기본값만 있다.
    private final ChatPersonaProperties personaProperties;
    // 칩 정의와 모듈 프롬프트도 같은 성격의 자산이다(chip-menu.yml, chip-modules.yml, 둘 다 gitignore).
    private final ChipStore chipStore;

    // 라벨 재작성을 끄는 스위치. 누르면 그대로 유저 말풍선이 되는 문장이라, 실측에서 이상하면
    // 코드를 고치지 않고 즉시 끌 수 있어야 한다. 끄면 카탈로그 원문이 그대로 뜬다.
    @Value("${chip.rewrite-label:true}")
    private boolean rewriteLabel;

    private final AssessmentRepository assessmentRepository;
    private final StoryRepository storyRepository;
    private final MessageRepository messageRepository;
    private final StoryMemoryRepository storyMemoryRepository;
    private final StoryFactRepository storyFactRepository;
    private final StoryIntakeRepository storyIntakeRepository;

    // tx1: 소유권 확인 + 유저 메시지 저장 + LLM에 보낼 프롬프트 조립. 짧게 끝난다.
    // 폴링 전환 후: 저장한 유저 메시지(즉시 응답용)와 프롬프트(백그라운드 LLM용)를 함께 돌려준다.
    @Transactional
    public PreparedSend appendUserMessageAndBuildPrompt(Long userId, Long storyId, String content,
                                                        String chipId) {
        Story story = storyRepository.findByIdAndUserIdAndDeletedAtIsNull(storyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
        // 카탈로그에 없는 id는 안 남긴다 — 지운 칩이 열려 있던 화면에서 뒤늦게 올 수 있고,
        // 남겨두면 기록만 오염되고 프롬프트에는 어차피 안 실린다.
        String known = chipStore.find(chipId) == null ? null : chipId;
        Message userMessage = messageRepository.save(Message.user(story, content, known));
        // 제목이 기본값이면 첫 메시지로 바꿔준다 — 목록이 "새 대화"만 줄지어 구분이 안 가는 문제.
        if (Story.DEFAULT_TITLE.equals(story.getTitle())) {
            story.rename(titleFrom(content));
        }
        // 프롬프트는 여기서 안 만든다 — 자유입력이면 그 사이에 저가 판별이 끼어 모듈을 정하고,
        // 그 결과가 행에 찍힌 뒤라야 프롬프트에 실린다(ChipMatcher 참고).
        return new PreparedSend(MessageResponse.from(userMessage), userMessage.getId());
    }

    // 판별이 끝난 뒤 백그라운드에서 부른다. 조회만 하지만 지연 로딩과 원장 조회가 있어 트랜잭션 안이다.
    @Transactional(readOnly = true)
    public List<ChatMessage> promptFor(Long storyId) {
        return buildPrompt(storyId);
    }

    // 칩 필터에만 쓰는 값이다. 프롬프트에 실리지 않으므로 상담자는 이 숫자를 못 본다 —
    // 진단 데이터는 진단을 다루는 자리에서만 실린다는 정책 그대로다(DiagnosisContext 참고).
    private Integer currentProbability(Long storyId) {
        return assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(storyId)
                .map(Assessment::getProbability)
                .orElse(null);
    }

    private String titleFrom(String content) {
        String oneLine = content.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= 20 ? oneLine : oneLine.substring(0, 20) + "…";
    }

    // 즉시 반환할 유저 메시지 + 그 행의 id(판별과 프롬프트 조립이 이 id로 이어진다).
    public record PreparedSend(MessageResponse userMessage, Long userMessageId) {}

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
        // 답변 끝의 내부 블록들은 읽어서 옮기고 본문에서는 뗀다 — 유저에게 보이라고 만든 문장이
        // 아니고, 남겨두면 다음 턴 프롬프트에도 실린다(ChatMeta, ChipTail 주석).
        story.updateReunionDirection(ChatMeta.direction(reply));
        List<SuggestedChip> chips = chipStore.parseTail(ChipTail.json(reply),
                currentProbability(storyId), rewriteLabel, MAX_LABEL_LENGTH, SUGGEST_COUNT);
        // 무엇을 골랐는지 남긴다. 칩이 안 뜨는 건 유저에게도 로그에도 흔적이 없는 고장이라,
        // 이 줄이 없으면 "조용히 안 나옴"의 원인을 밖에서 알 수 없다(실측으로 한 번 겪었다).
        // 나중에 칩별 추천 빈도를 세는 자리이기도 하다.
        log.info("칩 추천 storyId={} → {}", storyId, chips);
        Message answer = Message.assistant(story,
                stripMarkdown(ChipTail.strip(ChatMeta.strip(reply))));
        answer.assignSuggestedChips(chipStore.encode(chips));
        messageRepository.save(answer);
        story.touch();
        return MessageResponse.from(answer, chipStore.views(chips));
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
        // 폼으로 받은 기본 정보. 원장보다 앞에 둔다 — 이건 이야기가 시작되기 전의 바탕이고,
        // 원장은 그 위에서 벌어진 일이다. 시간이 지나 어긋나면 뒤에 오는 원장이 이긴다.
        storyIntakeRepository.findByStoryId(storyId)
                .map(StoryIntakeService::describe)
                .filter(block -> block != null && !block.isBlank())
                .ifPresent(block -> prompt.add(ChatMessage.system(block)));
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
        // 진단 데이터는 매 턴 실리지 않는다. 확률이 한 번 나오면 그 숫자가 너무 강한 기준점이
        // 되어, 새로 판단해야 하는 질문까지 기존 결론을 유지해 설명하는 쪽으로 흐른다.
        // 저장은 그대로 하고 필요한 자리에서만 꺼낸다(DiagnosisContext 주석 참고).
        ChipDefinition chip = clickedChip(recent);
        DiagnosisContext context = diagnosisContext(chip, recent);
        if (context != DiagnosisContext.NONE) {
            assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(storyId)
                    .ifPresent(a -> prompt.add(ChatMessage.system(describeAssessment(a, context))));
        }
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
        int answerNo = answerNo(recent);
        for (String section : sectionsFor(recent, answerNo)) {
            if (section != null && !section.isBlank()) {
                prompt.add(ChatMessage.system(section));
            }
        }
        String turnGuide = personaProperties.turnGuide(answerNo);
        if (turnGuide != null && !turnGuide.isBlank()) {
            prompt.add(ChatMessage.system(turnGuide));
        }
        // 다음 상담으로 들어갈 추천 질문을 이 답변과 함께 뽑게 한다. 별도 호출로 나눴을 때는
        // 답변이 저장되고 13~17초 있다 칩이 도착해, 그동안 화면이 "다 됐냐"를 반복해 물었다.
        // 1회차에는 안 싣는다 — 그 자리는 질문 카드가 쓰고, 아직 고를 근거도 없다.
        if (answerNo >= CHIP_FROM_ANSWER_NO) {
            String inline = chipStore.inlinePrompt();
            if (inline != null && !inline.isBlank()) {
                prompt.add(ChatMessage.system(inline + "\n\n"
                        + chipStore.catalogBlock(currentProbability(storyId))));
            }
        }
        // 출력 직전 점검은 반드시 대화 뒤, 프롬프트의 맨 끝이다 — 앞에 두면 리마인더와 같은
        // 자리가 되어 같은 이유로 묻힌다. 여기가 마지막으로 읽히는 지시라는 게 이 블록의 전부다.
        String finalCheck = personaProperties.finalCheckFor(answerNo);
        if (finalCheck != null && !finalCheck.isBlank()) {
            prompt.add(ChatMessage.system(finalCheck));
        }
        return prompt;
    }


    // 이번 턴에 실을 규칙 묶음. 유저가 추천 질문 칩을 눌러 들어왔으면 회차별 묶음 대신
    // 칩 공용 + 모듈 + 마이크로 프롬프트가 그 자리를 대신한다.
    //
    // 더하지 않고 대신하는 이유: 행동 상담은 칩 쪽(ACTION 모듈)으로 분리했으므로 회차 묶음의
    // action까지 같이 실으면 같은 일을 두 벌로 시킨다. question도 뺀다 — 유저가 고른 질문에
    // 답하는 턴이라 되묻기를 시킬 자리가 아니다.
    //
    // 관계심리만 남긴다. 무엇을 할지가 아니라 애착, 관계패턴 같은 개념을 어떻게 다룰지의
    // 어휘 규칙이라 어떤 턴이든 빠지면 용어 남용을 막을 것이 없다(sectionsFor 주석과 같은 이유).
    private List<String> sectionsFor(List<Message> recent, int answerNo) {
        ChipDefinition chip = clickedChip(recent);
        if (chip == null) {
            return personaProperties.sectionsFor(answerNo);
        }
        return List.of(personaProperties.getRelationshipPsychology(),
                chipStore.commonPrompt(),
                chipStore.modulePrompt(chip),
                chipStore.microPrompt(chip));
    }

    // 이번 호출에 진단 데이터를 얼마나 실을지.
    // 칩 턴이면 그 모듈의 정책을 따르고, 자유입력이면 기본은 안 싣되 유저가 진단 자체를
    // 물었을 때만 전부 싣는다. 판정은 코드로 한다 — 분류용 LLM을 하나 더 두면 호출이 늘고,
    // 이건 AI에 맡길 수준의 판정이 아니다(DiagnosisMention 주석 참고).
    private DiagnosisContext diagnosisContext(ChipDefinition chip, List<Message> recent) {
        if (chip != null) {
            return chipStore.diagnosisContext(chip);
        }
        if (recent.isEmpty() || recent.get(0).getRole() != MessageRole.USER) {
            return DiagnosisContext.NONE;
        }
        return DiagnosisMention.referenced(recent.get(0).getContent())
                ? DiagnosisContext.FULL
                : DiagnosisContext.NONE;
    }

    // 이번 턴을 연 유저 메시지가 칩에서 왔는가. 요청이 아니라 저장된 행을 보는 이유는 재시도다 —
    // 재시도는 유저 메시지를 그대로 두고 프롬프트만 다시 조립하므로, 행에 남아 있지 않으면
    // 재시도한 답변만 전문 모듈 없이 나온다.
    private ChipDefinition clickedChip(List<Message> recent) {
        if (recent.isEmpty()) {
            return null;
        }
        Message last = recent.get(0);
        return last.getRole() == MessageRole.USER ? chipStore.find(last.effectiveChipId()) : null;
    }

    // 이 턴이 이 사연의 첫 답변인지. 어시스턴트 메시지가 아직 없으면 첫 답변이다.
    // (recent는 방금 저장한 유저 메시지를 포함한 최신 N개다. 창 밖으로 밀려날 만큼 대화가
    // 길면 당연히 첫 답변이 아니므로 창 안만 봐도 충분하다.)
    // 진단을 재료로 옮긴 블록. 확률은 강도의 기준점으로만 주고, 나머지는 판정 라벨이 아니라
    // 관찰된 사실과 그 방향으로 적는다 — 유형, 점프, 요인 이름은 유저의 언어가 아니라 내부
    // 어휘라 프롬프트에 없으면 새어나갈 수도 없다. 중립(근거 없음) 슬롯은 판을 안 움직여 뺀다.
    private String describeAssessment(Assessment assessment, DiagnosisContext context) {
        StringBuilder block = new StringBuilder("최근 진단에서:\n");
        // 확률은 전부 싣는 자리에서만 나간다. 요인과 관찰 지점을 보는 자리에는 굳이 필요 없고,
        // 숫자가 함께 있으면 결국 그 숫자를 지키는 답이 된다.
        if (context.loadsProbability() && assessment.getProbability() != null) {
            block.append("재회 가능성 ").append(assessment.getProbability()).append("%\n");
        }
        if (context.loadsFactors()) {
            List<AssessmentFactor> weighted = assessment.getFactors().stream()
                    .filter(f -> f.getLevel() != FactorLevel.NEUTRAL)
                    .filter(f -> f.getEvidence() != null && !f.getEvidence().isBlank())
                    .toList();
            if (!weighted.isEmpty()) {
                block.append("무겁게 본 것:\n");
                for (AssessmentFactor factor : weighted) {
                    block.append("- ").append(factor.getEvidence().trim())
                            .append(" (").append(direction(factor.getLevel())).append(")\n");
                }
            }
        }
        // 관계 심리 라벨은 싣는다 — 유형/요인명과 달리 유저에게 말하라고 만든 어휘다.
        // 진단이 원본, 채팅이 인용: 채팅이 같은 관계에 딴 이름(요구-철회 vs 추구-회피)을
        // 지어 진단 화면과 어긋나는 것을 막는다. 설명 문장은 안 싣는다(지난 서사 반복 방지).
        if (context.loadsPsychology()) {
            String psychology = describePsychology(assessment.getRelationshipPsychology());
            if (psychology != null) {
                block.append(psychology).append('\n');
            }
        }
        if (context.loadsRelapseRisk() && assessment.getRelapseRisk() != null) {
            block.append("재발 위험=").append(assessment.getRelapseRisk().label());
            if (assessment.getRelapseReason() != null && !assessment.getRelapseReason().isBlank()) {
                block.append(" (").append(assessment.getRelapseReason().trim()).append(')');
            }
            block.append('\n');
        }
        if (context.loadsWatchPoints() && !assessment.getWatchPoints().isEmpty()) {
            block.append("지켜볼 것:\n");
            for (WatchPoint watch : assessment.getWatchPoints()) {
                block.append("- ").append(watch.getPoint())
                        .append(" → ").append(watch.getEffect()).append('\n');
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

    private String describePsychology(RelationshipPsychology psychology) {
        if (psychology == null) {
            return null;
        }
        // 보류값은 싣지 않는다 — 상담자에게 "판단보류"를 알려줘도 쓸 말이 없다.
        List<String> bits = new ArrayList<>();
        RelationshipPsychology.PatternItem pattern = psychology.interactionPattern();
        if (pattern != null && !RelationshipPsychology.PATTERN_UNDECIDED.equals(pattern.label())) {
            bits.add("관계 패턴=" + pattern.label());
        }
        RelationshipPsychology.Attachment attachment = psychology.attachment();
        if (attachment != null) {
            if (judged(attachment.user())) {
                bits.add("유저의 애착 경향=" + attachment.user().label());
            }
            if (judged(attachment.partner())) {
                bits.add("상대의 애착 경향=" + attachment.partner().label());
            }
        }
        RelationshipPsychology.NeedConflict needs = psychology.needConflict();
        if (needs != null && needs.left() != null && needs.right() != null) {
            bits.add("핵심 욕구 유저=" + needs.left() + " 상대=" + needs.right());
        }
        return bits.isEmpty() ? null : "진단이 판정한 관계 심리: " + String.join(", ", bits);
    }

    private boolean judged(RelationshipPsychology.Style style) {
        return style != null && !RelationshipPsychology.ATTACHMENT_UNDECIDED.equals(style.label());
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

    // 지금 만들 답이 몇 번째인지(1부터). 이미 붙은 상담자 답의 수 + 1이다.
    // 첫 화면 인사 말풍선은 저장되지 않으므로 세지 않는다.
    private int answerNo(List<Message> recent) {
        return (int) recent.stream().filter(message -> message.getRole() != MessageRole.USER).count() + 1;
    }


}
