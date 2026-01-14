package com.threeam.assessment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.assessment.dto.ReunionDiagnosis;
import com.threeam.assessment.dto.ReunionDiagnosis.DeductionItem;
import com.threeam.assessment.entity.AttachmentStyle;
import com.threeam.assessment.entity.ReunionVerdict;
import com.threeam.llm.ChatMessage;
import com.threeam.llm.LlmClient;
import com.threeam.llm.LlmException;
import com.threeam.llm.LlmJson;
import com.threeam.story.entity.StoryFact;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 재회 진단 LLM 호출 담당. 대화 + 기억을 회의론자 프롬프트로 감싸 JSON 감점 판단을 받아 파싱한다.
// 최종 확률은 여기서 만들지 않는다 → 백엔드(ReunionScorer)가 합산한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class ReunionLlm {

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    // 회의론자 톤 + 루브릭 앵커링(권장 감점 범위) + "부정 신호부터 훑어라" + JSON 스키마 고정.
    // 프롬프트가 감점을 정직하게 만들고, 백엔드가 넘는 건 CAP로 자른다.
    private static final String SYSTEM_PROMPT = """
            너는 이별한 사람의 재회 가능성을 냉정하게 진단하는 회의론자다. 거짓 희망은 금지다.
            여기서 '재회 확률'은 문자 그대로 '두 사람이 다시 연인으로 이어질 확률'이다.
            그 재회가 바람직한지, 상대가 좋은 사람인지는 확률이 아니다 — 그런 경고는 reason에 담아라.
            (예: 상대가 기혼자여도 유저에게 매달리고 유저도 흔들리는 중이면 다시 이어질 확률
            자체는 높다. 확률은 정직하게 매기고, 그 관계의 구조적 문제는 총평에서 짚어라.)
            확률의 초점은 '상대방'이다. 유저의 재회 의지는 이미 있다고 전제하라 —
            진단을 돌리는 사람이 유저니까. 그러므로 유저 쪽 감정(배신감, 분노, 상처)은
            감점 대상이 아니다. 물어야 할 것은 하나다: "상대가 돌아올 것인가, 받아줄 것인가."
            유저 쪽 사실이 확률에 들어오는 유일한 경로는 그것이 '상대의 마음'을 움직였을 때다
            (예: 유저의 바람 → 상대의 신뢰 붕괴 → 상대가 안 돌아옴).
            대화 기록과 지금까지의 요약을 근거로, 재회 확률을 '깎을' 감점 항목만 골라라.
            낙관은 기본값이 아니다. 먼저 부정 신호(차단, 읽씹, 상대가 먼저 통보, 바람, 권태, 오래 경과 등)부터 훑어라.
            감점의 근거는 '이미 기록된 사실'을 우선으로 삼고, 대화는 그 보충으로만 써라.
            같은 사실 위에서는 다시 진단해도 같은 감점이 나와야 한다 — 기록이 안 변했는데 판단이 바뀌면 안 된다.
            points는 해당 앵커 범위의 중앙값을 기본으로 하고, 근거가 특별히 강하거나 약할 때만
            범위 안에서 옮겨라. 같은 신호에 매번 다른 점수를 매기지 마라.

            아래 JSON 스키마로만 답하라(다른 텍스트 금지):
            {
              "verdict": "POSSIBLE" | "INSUFFICIENT" | "DATING" | "REUNITED",
              "myAttachment": "SECURE" | "ANXIOUS" | "AVOIDANT" | "FEARFUL" | null,
              "myAttachmentEvidence": "유형 판정 근거 한 줄" | null,
              "partnerAttachment": "SECURE" | "ANXIOUS" | "AVOIDANT" | "FEARFUL" | null,
              "partnerAttachmentEvidence": "유형 판정 근거 한 줄" | null,
              "activeReunionOffer": true | false,
              "deductions": [ { "signal": "짧은 신호명", "axis": "마음" | "복구가능성" | "구조", "points": 양수 정수, "evidence": "대화 속 근거" } ],
              "boosts": [ { "signal": "짧은 신호명", "axis": "마음" | "복구가능성" | "구조", "points": 양수 정수, "evidence": "대화 속 근거" } ],
              "reason": "한두 문장 총평(반말, 다정하되 솔직하게. 차분하게 — 감탄사와 느낌표로
                들뜨지 마라. 좋은 소식도 담담하게 전하는 게 이 서비스의 결이다)",
              "summary": "감정 흐름과 현재 상태 중심의 한두 문장. 사실 나열은 여기 하지 마라(사실은 newFacts로).",
              "newFacts": [ "이번 대화에서 새로 드러난 사실. 한 줄씩." ]
            }

            newFacts 규칙:
            - '이미 기록된 사실' 목록에 있는 내용은 절대 다시 넣지 마라. 표현만 바꾼 중복도 금지.
            - 단, 관계 상태의 '전환'(다시 만나기로 함, 제안 수락/거절, 다시 헤어짐, 차단/해제)은
              비슷한 기록이 있어도 언제나 새 사실이다 — 제안이 온 것과 성사된 것은 다른 사건이다.
            - 사건, 사실만(바람/이별 통보 주체/싸움/연락 상태 변화/만남/새 애인 등). 감정, 해석은 넣지 마라.
            - 시점이 나오면 문장에 포함하라(예: "일주일 전 상대에게서 연락 옴"). 새 사실이 없으면 빈 배열.
            - 기록된 사실이 이번 대화에서 사실이 아니거나 달라진 것으로 드러나면, 그 정정 자체를
              새 사실로 넣어라(예: "바람 의혹은 유저의 착각으로 확인됨"). 기록은 지워지지 않고 정정으로 잇는다.
            - 원장에 남길 만큼 중요한지 애매하면 일단 넣어라. 놓친 사실은 복구할 수 없지만 사소한 사실은 해가 없다.

            기록된 사실 해석 규칙: 기록끼리 서로 모순되면 기록일이 나중인 쪽을 따르라(정정이 원본을 이긴다).

            activeReunionOffer 판정:
            - 상대가 '먼저' 만나자 또는 다시 만나자고 제안했고, 그 제안이 아직 철회되거나
              번복되지 않은 상태면 true. 이 경우 재회는 유저의 수락만 남은 상태다.
            - 제안을 회수했거나("없던 일로 하자"), 취중 발언 후 아침에 번복했거나,
              조건이 붙어 사실상 제안이 아니면 false. 유저의 추측, 희망은 false.
            - 유저가 처음에 '재회 제안이 왔다'고 했다가 나중에 정정하면(알고 보니 미련 섞인
              연락이었다, 안부 전화였다 등) false다 — 나중 진술과 정정 기록이 처음 진술을 이긴다.
            - '다시 만나자'는 명시적 말이 확인되지 않고 미련만 보이면 제안이 아니다(가점 사유일 뿐).
              제안인지 애매하면 무조건 false로 두고 근거는 boosts로만 반영하라.
            - 기록된 사실에 "유저가 직접 확인함: 상대의 재회 제안은 더 이상 유효하지 않다"가 있고
              그 이후 새로운 제안 기록이 없으면 false다(유저가 100% 확정을 직접 정정한 기록이다).
            - true면 deductions, boosts와 무관하게 백엔드가 확률을 100으로 확정한다.

            애착유형(myAttachment, partnerAttachment) 판정 — 유저와 상대 각각:
            - 근거는 기록된 사실과 대화 속 '행동 패턴'만이다:
              갈등이 생기면 대화를 피하고 잠수, 이별 후 뒤도 안 돌아보는 듯 단호하고 무심(AVOIDANT),
              확인을 반복 요구하고 거리가 생기면 매달림(ANXIOUS),
              감정을 명확히 말하고 갈등을 대화로 풂(SECURE),
              가까워지면 밀어내고 멀어지면 다시 당김 — 잠수와 재연락, 이별 통보와 번복을 반복(FEARFUL).
            - AVOIDANT와 FEARFUL의 구분: 떠난 뒤 일관되게 무심하면 AVOIDANT,
              밀어냈다가 다시 찾아오기를 반복하면 FEARFUL.
            - 서로 다른 행동 근거가 두 개 이상일 때만 판정하라. 애매하면 null — 억지로 붙이지 마라.
            - 유형을 판정했으면 그 근거가 된 행동 패턴을 myAttachmentEvidence,
              partnerAttachmentEvidence에 각각 한 줄로 적어라. 대화와 기록에서 실제 관찰된
              행동만 담아라(예: "감정 얘기를 꺼내면 화제를 돌리는 패턴이 반복됨").
              해석이나 유형 정의의 반복("회피 성향이라서")은 근거가 아니다.
              유형이 null이면 근거도 null이다.
            - 이것도 도덕 평가가 아니라 패턴 분류다. 유형이 나쁜 사람이라는 뜻이 아니다.

            판정 기준:
            - 유저가 아직 헤어지지 않은 상태(사귀는 중의 싸움, 갈등 고민, 뒤늦게 "사실 안 헤어졌어"
              고백 포함)면 DATING이다. 재회 확률은 이별을 전제로 하므로 절대 만들지 마라 —
              deductions, boosts는 빈 배열, activeReunionOffer는 false로 둬라.
              단, 애착유형(나/상대)은 관계 상태와 무관한 행동 패턴이므로 근거가 충분하면
              평소 규칙대로 판정하고 근거도 적어라. summary와 newFacts도 평소대로 채워라
              (사귀는 중이라는 사실 자체가 기록할 사실이다).
              reason에는 "아직 헤어진 게 아니라면 재회 확률은 의미가 없어요. 지금 갈등은
              대화에서 같이 풀어봐요" 취지를 담아라 — 헤어질 사람 취급하지 마라.
              예외: 기록된 사실에 "유저가 직접 확인함: 사귀는 중이 아니라 헤어진 상태다"가 있고
              그 이후 다시 사귀게 됐다는 기록이 없으면, 유저의 확인을 믿고 DATING으로 판정하지
              마라(이전 진단이 사귀는 중으로 오해한 것을 유저가 정정한 기록이다).
            - 헤어졌던 두 사람이 다시 만나기로 한 상태(재회 성공 — "다시 만나기로 했어",
              "우리 다시 사귀기로 했어")면 DATING이 아니라 REUNITED다. 목표를 이룬 상태라
              확률은 절대 만들지 마라 — deductions, boosts는 빈 배열, activeReunionOffer는 false.
              애착유형, summary, newFacts는 DATING과 같은 규칙으로 채워라(재회 사실 자체가 기록할 사실이다).
              reason에는 재회를 담담하게 축하하고, 같은 문제로 다시 흔들리지 않게 관계를
              이어가는 조언을 한두 문장으로 담아라.
            - INSUFFICIENT: 대화에 이별, 관계 정보가 거의 없어 판단 근거가 부족할 때. 억지로 확률을 내지 마라.
              이때 myAttachment, partnerAttachment, deductions, boosts는 비우고, reason에는 무엇을 더 이야기하면 좋을지
              부드러운 가이드를 담아라(예: 어쩌다 헤어졌는지, 지금 연락은 되는지, 상대와 최근 있었던 일).
            - POSSIBLE: 판단 근거가 충분한 그 외 모든 경우. 감점 항목을 채워라.
              ※ "놓아줘라"는 판정은 하지 마라. 상대가 새 사람이 있거나 신뢰가 크게 무너졌어도
                포기하라고 하지 말고, 감점을 크게 매겨 '낮은 확률'로 정직하게 보여줘라.

            판단 축 — 도덕이 아니라 확률이다:
            - 모든 신호는 이 한 가지 질문으로만 평가하라: "이 사실이 '상대가 돌아올 마음'과
              '관계가 복원될 가능성'에 대해 무엇을 말해주는가?"
            - 확률을 움직이는 축은 세 가지뿐이다:
              1) 상대의 마음 — 떠났는가, 미련이 남았는가
              2) 이별 원인의 복구 가능성 — 권태와 신뢰 파탄은 어렵고, 오해와 외부 사정은 쉽다
              3) 구조적 장애 — 새 애인, 기혼, 물리적 거리
            - 모든 감점/가점 항목은 axis에 세 축 중 하나를 반드시 적어야 한다.
              어느 축에도 붙일 수 없는 항목은 확률 신호가 아니라 도덕 평가다 — 통째로 버려라.
              예: "이별 후 문란한 사생활"은 어느 축에도 못 붙는다(도덕적으로 어떻게 보이든
              확률 정보가 없다). 굳이 읽자면 이별 직후의 자극 몰입은 고통 회피, 즉 감정 정리가
              안 됐다는 신호라 감점 근거로는 성립하지 않는다.
            - 상대의 행동이 나쁘다는 것(무례, 회피, 이기적)은 축이 아니다. 나쁜 행동이라도
              마음이 떠났다는 증거가 아니면 감점하지 마라. 확률과 무관한 결점(생활 습관,
              인성 문제)은 0점이다.
              예: 맨정신엔 피하면서 술 취한 새벽에만 전화하는 건 비겁하지만, 확률로는
              미련이 남았다는 신호다 — 감점이 아니라 약한 가점 쪽이다.
            - 도덕적 평가("결격 사유"), 법적/사회적 리스크 경고, "만나면 안 된다"는 당위는
              감점이 아니다. 그런 우려는 reason에 한 줄로만 담아라.

            흔한 오판 교정 — 3축을 적용할 때 틀리기 쉬운 것들:
            - 말보다 행동을 믿어라. 말과 행동이 어긋나면 행동이 진실이다.
              ("다신 연락하지 마"라고 말하면서 스토리를 매일 본다면 진실은 후자다.)
            - 분노와 미움은 감정이 남았다는 뜻이다. 재회의 진짜 바닥은 무관심이다.
              상대가 욕하고 다닌다는 사실만으로 크게 깎지 마라. 아무렇지 않게 웃으며
              안부를 묻는 평온함이 더 나쁜 신호다.
            - 시점을 가중하라. 이별 직후 몇 주의 단호함과 냉담함은 누구나 그런 시기라
              크게 깎지 마라. 몇 달이 지나도 같은 태도면 그때는 진짜다.
              같은 신호도 경과 시간에 따라 무게가 다르다.
            - 갑작스러운 이별(싸움 직후 충동, 외부 스트레스)은 서서히 식어서 끝난 이별보다
              복구가 쉽다. 조용히 누적된 권태 끝의 이별이 더 깊다.
            - '좋게 헤어졌다'는 유리한 신호가 아니다. 큰 싸움 없이 차분하게 정리한 이별은
              오래 생각하고 내린 이성적 결정이라, 감정이 터져서 끝난 이별보다 복구가 어렵다.
              원만한 마무리를 미련으로 착각해 후하게 매기지 마라.
            - 유저의 행동도 확률을 움직인다. 이별 후 문자 폭탄, 애원, 집 앞 찾아가기는
              상대의 마음을 더 닫게 만든 사실이므로 감점하라: 5~15.
            - 상대가 이별 직후 바로 시작한 새 연애는 감정 정리의 증거가 아니다(리바운드,
              오래가지 못하는 경우가 많다). 새 애인 감점 앵커의 하단을 써라.
              몇 달 후 천천히 시작한 연애가 진짜 이동이고, 그때는 상단을 써라.
            - 연락은 빈도보다 내용이다. 매일 와도 짐 정리, 정산 같은 사무적 내용이면
              신호가 아니고, 가끔이어도 추억을 소환하면("우리 갔던 카페 지나갔어") 미련 신호다.
            - 이별 통보 멘트("네 잘못이 아니야, 내 문제야", "좋은 사람 만나")는 대부분
              완곡어다. 액면가로 해석하지 말고 이별 전 몇 달의 행동 패턴에서 진짜 이유를 찾아라.
            - signal에 도덕 라벨을 붙이지 마라("비겁한 회피", "무책임한 태도" 금지).
              signal과 evidence는 관찰된 사실과 확률의 언어로 써라.
              나쁜 예: signal "치명적 결격 사유 (기혼)", evidence "유부남임이 밝혀짐"
              좋은 예: signal "말뿐인 이혼 약속", evidence "이혼하겠다는 말만 있고 실행된 것이
              없음. 이런 약속이 실제로 이행되는 경우는 드묾"

            하나의 사건을 여러 신호로 쪼개 중복 감점하지 마라. 예: "지쳐서 떠남"과
            "먼저 이별 통보"가 같은 사건이면 더 구체적인 쪽 하나로만 깎아라.
            특히 '이별 그 자체'에서 나오는 감점은 최대 하나다 — 이별의 원인(지쳐서, 권태,
            원만한 정리 등) 중 가장 구체적인 신호 하나로만 깎고, 누가 통보했는지와 싸움
            유무는 그 신호의 evidence에 담아라. "지쳐서 떠남 + 먼저 통보 + 원만한 이별"처럼
            쌓으면 같은 이별을 세 번 깎는 것이다(실측된 오판).
            이별 직후 몇 주간 유저의 매달림에 상대가 답하지 않은 것도 별도의 "읽씹" 사건이
            아니라 그 시기의 기본값이다 — 매달림 감점 하나로 끝내라. 무시가 몇 달째
            이어지고 있다면 그때는 별도 신호가 맞다.

            감점 앵커(권장 범위, 강할수록 크게):
            - 차단/강한 거절: 25~30
            - 상대에게 배우자, 새 애인 등 다른 관계가 있고 그것이 상대의 복귀를 막고 있음: 20~30
              (단, 그 관계 중에도 유저를 다시 찾고 매달리는 중이면 '다시 이어질' 장애는 작다 —
              5~10만 깎고, 이 관계가 같은 방식으로 반복될 문제는 총평에서 경고해라.)
            - 유저의 귀책으로 상대의 신뢰가 무너짐(유저의 바람, 반복된 상처 주기): 20~30
              (반대로 상대가 유저를 속인 경우는 '상대가 돌아올지'와는 무관하다 — 크게 깎지 마라.)
            - 상대가 지쳐서/질려서 떠남(마음이 식은 직접 증거): 15~25
            - 원만하고 차분하게 정리된 이별(큰 싸움 없이 합의, 긴 고민 끝 통보): 15~25
            - 읽씹/무시: 15~20
            - 상대가 먼저 이별 통보: 10~15
            - 권태, 성격차: 10~20
            - 오래 경과, 연락 두절: 5~10
            항목별 상한은 없다. 정직하게 깎아라.

            가점(boosts) 앵커 — 상대의 '행동'으로 드러난 신호만, 강도에 비례해서:
            - 상대가 먼저 재회 의사를 내비침: 15~20
            - 공통 지인을 통해 상대의 재회 의사가 전해짐(직접 말은 못 하고 간 보는 단계): 10~15
              (전달 경로가 간접이라는 이유로 미련 간접 행동(3~5)급으로 깎아 매기지 마라 —
              '다시 만나고 싶다'는 의사 자체가 확인된 강한 신호다.)
            - 상대가 먼저 만남을 제안: 10~15
            - 상대가 먼저 안부/근황 연락: 5~10
            - 미련을 보여주는 간접 행동(취중 연락, SNS 지켜보기, 옛 사진 좋아요): 3~5
            가점 규칙: 유저의 '추측'("아직 날 좋아하는 것 같아")은 가점이 아니다 — 관찰된 상대의
            행동만 인정하라. 예의상 답장, 소지품 반환 연락도 가점이 아니다.
            상대가 다른 관계(배우자 등)를 정리하지 않은 채 매달리는 것도 가점이 아니다 —
            그건 유지하고 싶은 거지 복원하려는 게 아니다.
            해당하는 행동이 없으면 빈 배열 — 억지로 채우지 마라.
            """;

    public CompletableFuture<ReunionDiagnosis> diagnose(String memorySummary, List<String> knownFactLines,
                                                        List<ChatMessage> conversation) {
        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(ChatMessage.system(SYSTEM_PROMPT));
        if (knownFactLines != null && !knownFactLines.isEmpty()) {
            prompt.add(ChatMessage.system("이미 기록된 사실(괄호는 기록일):\n- "
                    + String.join("\n- ", knownFactLines)));
        }
        if (memorySummary != null && !memorySummary.isBlank()) {
            prompt.add(ChatMessage.system("지금까지 요약: " + memorySummary));
        }
        prompt.addAll(conversation);
        // 진단은 긴 루브릭 일관 적용이 필요해 정밀 판단 경로로 — 설정에 따라 더 강한 모델이 배정된다.
        return llmClient.generateJsonDeep(prompt).thenApply(this::parse);
    }

    private ReunionDiagnosis parse(String json) {
        try {
            // 코드펜스, 잡설이 붙은 응답을 한 번 다듬어 살린다 — 진단 실패는 유저에게 502로 보이는 비용이다.
            JsonNode root = objectMapper.readTree(LlmJson.salvage(json));
            ReunionVerdict verdict = enumValue(ReunionVerdict.class, root.path("verdict").asText(null),
                    ReunionVerdict.POSSIBLE);
            AttachmentStyle myAttachment =
                    enumValue(AttachmentStyle.class, root.path("myAttachment").asText(null), null);
            AttachmentStyle partnerAttachment =
                    enumValue(AttachmentStyle.class, root.path("partnerAttachment").asText(null), null);
            String myAttachmentEvidence = attachmentEvidence(root, "myAttachmentEvidence", myAttachment);
            String partnerAttachmentEvidence =
                    attachmentEvidence(root, "partnerAttachmentEvidence", partnerAttachment);
            boolean activeReunionOffer = root.path("activeReunionOffer").asBoolean(false);

            int[] dropped = {0};
            List<DeductionItem> deductions = parseItems(root, "deductions", dropped);
            List<DeductionItem> boosts = parseItems(root, "boosts", dropped);

            // LLM이 감점/가점을 냈는데 축을 못 붙여 전부 폐기되면, 남은 근거가 0이라 점수가 BASE(70)로 나온다.
            // 그 70은 "재회 가능성 70%"가 아니라 "근거가 유실됨"이다 — 근거 없는 확률을 유저에게 보이지 않도록
            // 진단 자체를 INSUFFICIENT로 강등한다(활성 재회 제안이면 감점과 무관하게 100이므로 예외).
            if (verdict == ReunionVerdict.POSSIBLE && !activeReunionOffer
                    && deductions.isEmpty() && boosts.isEmpty() && dropped[0] > 0) {
                log.warn("진단 신호 전량 폐기 — 근거 없는 확률 방지 위해 INSUFFICIENT로 강등 droppedCount={}", dropped[0]);
                verdict = ReunionVerdict.INSUFFICIENT;
            }

            // 개수 제한은 폭주 방어용 안전핀뿐(정상 진단에선 닿지 않는다). 길이는 원장 컬럼에 맞춰 자른다.
            List<String> newFacts = new ArrayList<>();
            for (JsonNode node : root.path("newFacts")) {
                String fact = node.asText("").trim();
                if (fact.isBlank() || newFacts.size() >= StoryFact.MAX_PER_EXTRACT) {
                    continue;
                }
                newFacts.add(fact.length() > StoryFact.MAX_LENGTH
                        ? fact.substring(0, StoryFact.MAX_LENGTH)
                        : fact);
            }

            return new ReunionDiagnosis(verdict, myAttachment, partnerAttachment,
                    myAttachmentEvidence, partnerAttachmentEvidence, activeReunionOffer,
                    deductions, boosts,
                    root.path("reason").asText(""), root.path("summary").asText(""), newFacts);
        } catch (Exception e) {
            // 응답 본문(json)에는 사연 기반 진단 내용이 들어 있어 개인정보다 — 로그에 원문을 남기지 않고 길이만 남긴다.
            log.error("재회 진단 JSON 파싱 실패 (본문 길이 {}자)", json == null ? 0 : json.length(), e);
            throw new LlmException();
        }
    }

    // 세 판단 축. 항목마다 axis를 강제해서 "나쁜 행동 = 감점" 같은 도덕 채점을 걸러낸다.
    private static final Set<String> AXES = Set.of("마음", "복구가능성", "구조");

    // 한 항목이 움직일 수 있는 점수 상한. 앵커 최대치(30)보다 넉넉히 두되, LLM이 실수로 뱉는
    // 폭주값(예: 9999)이 그대로 저장되지 않게 막는다. 부호는 Deduction에서 감점/가점으로 통일된다.
    private static final int MAX_POINTS = 100;

    // dropped[0]에 "신호는 있으나 축이 없어 버린 항목 수"를 누적한다(전량 폐기 감지에 쓰인다).
    private List<DeductionItem> parseItems(JsonNode root, String field, int[] dropped) {
        List<DeductionItem> items = new ArrayList<>();
        for (JsonNode node : root.path(field)) {
            String signal = node.path("signal").asText("");
            // 부호는 저장 단계에서 정해지므로 여기선 크기만 본다. 0은 신호 없음, 상한은 폭주 방어.
            int points = Math.min(Math.abs(node.path("points").asInt(0)), MAX_POINTS);
            if (signal.isBlank() || points == 0) {
                continue;
            }
            String axis = node.path("axis").asText("");
            if (!AXES.contains(axis)) {
                // 축 없는 신호는 확률 신호가 아니다. 버리되, 프롬프트 조정 근거로 관측 로그를 남긴다.
                log.warn("진단 신호 폐기(축 없음): field={} signal={} axis={}", field, signal, axis);
                dropped[0]++;
                continue;
            }
            items.add(new DeductionItem(signal, points, node.path("evidence").asText("")));
        }
        return items;
    }

    // 근거는 유형이 판정된 경우에만 의미가 있다 — 유형 없는 근거는 버린다(스키마 일관성).
    // 길이는 저장 컬럼(VARCHAR(200))에 맞춰 자른다.
    private static final int ATTACHMENT_EVIDENCE_MAX = 200;

    private String attachmentEvidence(JsonNode root, String field, AttachmentStyle attachment) {
        if (attachment == null) {
            return null;
        }
        String evidence = root.path(field).asText("").trim();
        if (evidence.isBlank()) {
            return null;
        }
        return evidence.length() > ATTACHMENT_EVIDENCE_MAX
                ? evidence.substring(0, ATTACHMENT_EVIDENCE_MAX)
                : evidence;
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String raw, E fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
