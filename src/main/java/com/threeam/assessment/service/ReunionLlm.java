package com.threeam.assessment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.assessment.dto.ReunionDiagnosis;
import com.threeam.assessment.dto.ReunionDiagnosis.DeductionItem;
import com.threeam.assessment.entity.BreakupType;
import com.threeam.assessment.entity.PartnerType;
import com.threeam.assessment.entity.ReunionVerdict;
import com.threeam.llm.ChatMessage;
import com.threeam.llm.LlmClient;
import com.threeam.llm.LlmException;
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
            여기서 '재회 확률'은 단순히 다시 연락이 닿거나 만날 확률이 아니라,
            유저가 원하는 형태의 관계가 실제로 복원될 확률이다.
            (예: 상대가 기혼자면 다시 만나기는 쉬워도 정식 관계가 될 확률은 낮다 — 낮은 쪽이 정답이다.)
            대화 기록과 지금까지의 요약을 근거로, 재회 확률을 '깎을' 감점 항목만 골라라.
            낙관은 기본값이 아니다. 먼저 부정 신호(차단, 읽씹, 상대가 먼저 통보, 바람, 권태, 오래 경과 등)부터 훑어라.
            감점의 근거는 '이미 기록된 사실'을 우선으로 삼고, 대화는 그 보충으로만 써라.
            같은 사실 위에서는 다시 진단해도 같은 감점이 나와야 한다 — 기록이 안 변했는데 판단이 바뀌면 안 된다.

            아래 JSON 스키마로만 답하라(다른 텍스트 금지):
            {
              "verdict": "POSSIBLE" | "INSUFFICIENT",
              "breakupType": "CLINGER" | "REGRETTER" | "SELF_BLAMER",
              "partnerType": "DECISIVE" | "AMBIVALENT" | "COLD",
              "deductions": [ { "signal": "짧은 신호명", "axis": "마음" | "복구가능성" | "구조", "points": 양수 정수, "evidence": "대화 속 근거" } ],
              "boosts": [ { "signal": "짧은 신호명", "axis": "마음" | "복구가능성" | "구조", "points": 양수 정수, "evidence": "대화 속 근거" } ],
              "reason": "한두 문장 총평(반말, 다정하되 솔직하게)",
              "summary": "감정 흐름과 현재 상태 중심의 한두 문장. 사실 나열은 여기 하지 마라(사실은 newFacts로).",
              "newFacts": [ "이번 대화에서 새로 드러난 사실. 한 줄씩." ]
            }

            newFacts 규칙:
            - '이미 기록된 사실' 목록에 있는 내용은 절대 다시 넣지 마라. 표현만 바꾼 중복도 금지.
            - 사건, 사실만(바람/이별 통보 주체/싸움/연락 상태 변화/만남/새 애인 등). 감정, 해석은 넣지 마라.
            - 시점이 나오면 문장에 포함하라(예: "일주일 전 상대에게서 연락 옴"). 새 사실이 없으면 빈 배열.
            - 기록된 사실이 이번 대화에서 사실이 아니거나 달라진 것으로 드러나면, 그 정정 자체를
              새 사실로 넣어라(예: "바람 의혹은 유저의 착각으로 확인됨"). 기록은 지워지지 않고 정정으로 잇는다.
            - 원장에 남길 만큼 중요한지 애매하면 일단 넣어라. 놓친 사실은 복구할 수 없지만 사소한 사실은 해가 없다.

            기록된 사실 해석 규칙: 기록끼리 서로 모순되면 기록일이 나중인 쪽을 따르라(정정이 원본을 이긴다).

            판정 기준:
            - INSUFFICIENT: 대화에 이별, 관계 정보가 거의 없어 판단 근거가 부족할 때. 억지로 확률을 내지 마라.
              이때 breakupType, partnerType, deductions, boosts는 비우고, reason에는 무엇을 더 이야기하면 좋을지
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

            감점 앵커(권장 범위, 강할수록 크게):
            - 차단/강한 거절: 25~30
            - 상대에게 배우자, 새 애인 등 정리되지 않은 다른 관계가 있음(약속이 말뿐인 단계면 크게): 20~30
            - 유저의 귀책으로 상대의 신뢰가 무너짐(유저의 바람, 반복된 상처 주기): 20~30
            - 상대가 지쳐서/질려서 떠남(마음이 식은 직접 증거): 15~25
            - 읽씹/무시: 15~20
            - 상대가 먼저 이별 통보: 10~15
            - 권태, 성격차: 10~20
            - 오래 경과, 연락 두절: 5~10
            항목별 상한은 없다. 정직하게 깎아라.

            가점(boosts) 앵커 — 상대의 '행동'으로 드러난 신호만, 강도에 비례해서:
            - 상대가 먼저 재회 의사를 내비침: 15~20
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
        return llmClient.generateJson(prompt).thenApply(this::parse);
    }

    private ReunionDiagnosis parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            ReunionVerdict verdict = enumValue(ReunionVerdict.class, root.path("verdict").asText(null),
                    ReunionVerdict.POSSIBLE);
            BreakupType breakupType = enumValue(BreakupType.class, root.path("breakupType").asText(null), null);
            PartnerType partnerType = enumValue(PartnerType.class, root.path("partnerType").asText(null), null);

            List<DeductionItem> deductions = parseItems(root, "deductions");
            List<DeductionItem> boosts = parseItems(root, "boosts");

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

            return new ReunionDiagnosis(verdict, breakupType, partnerType, deductions, boosts,
                    root.path("reason").asText(""), root.path("summary").asText(""), newFacts);
        } catch (Exception e) {
            log.error("재회 진단 JSON 파싱 실패: {}", json, e);
            throw new LlmException();
        }
    }

    // 세 판단 축. 항목마다 axis를 강제해서 "나쁜 행동 = 감점" 같은 도덕 채점을 걸러낸다.
    private static final Set<String> AXES = Set.of("마음", "복구가능성", "구조");

    private List<DeductionItem> parseItems(JsonNode root, String field) {
        List<DeductionItem> items = new ArrayList<>();
        for (JsonNode node : root.path(field)) {
            String signal = node.path("signal").asText("");
            int points = node.path("points").asInt(0);
            if (signal.isBlank() || points == 0) {
                continue;
            }
            String axis = node.path("axis").asText("");
            if (!AXES.contains(axis)) {
                // 축 없는 신호는 확률 신호가 아니다. 버리되, 프롬프트 조정 근거로 관측 로그를 남긴다.
                log.warn("진단 신호 폐기(축 없음): field={} signal={} axis={}", field, signal, axis);
                continue;
            }
            items.add(new DeductionItem(signal, points, node.path("evidence").asText("")));
        }
        return items;
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
