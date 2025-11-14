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
            대화 기록과 지금까지의 요약을 근거로, 재회 확률을 '깎을' 감점 항목만 골라라.
            낙관은 기본값이 아니다. 먼저 부정 신호(차단·읽씹·상대가 먼저 통보·바람·권태·오래 경과 등)부터 훑어라.
            감점의 근거는 '이미 기록된 사실'을 우선으로 삼고, 대화는 그 보충으로만 써라.
            같은 사실 위에서는 다시 진단해도 같은 감점이 나와야 한다 — 기록이 안 변했는데 판단이 바뀌면 안 된다.

            아래 JSON 스키마로만 답하라(다른 텍스트 금지):
            {
              "verdict": "POSSIBLE" | "INSUFFICIENT",
              "breakupType": "CLINGER" | "REGRETTER" | "SELF_BLAMER",
              "partnerType": "DECISIVE" | "AMBIVALENT" | "COLD",
              "deductions": [ { "signal": "짧은 신호명", "points": 정수, "evidence": "대화 속 근거" } ],
              "reason": "한두 문장 총평(반말, 다정하되 솔직하게)",
              "summary": "감정 흐름과 현재 상태 중심의 한두 문장. 사실 나열은 여기 하지 마라(사실은 newFacts로).",
              "newFacts": [ "이번 대화에서 새로 드러난 사실. 한 줄씩." ]
            }

            newFacts 규칙:
            - '이미 기록된 사실' 목록에 있는 내용은 절대 다시 넣지 마라. 표현만 바꾼 중복도 금지.
            - 사건·사실만(바람/이별 통보 주체/싸움/연락 상태 변화/만남/새 애인 등). 감정·해석은 넣지 마라.
            - 시점이 나오면 문장에 포함하라(예: "일주일 전 상대에게서 연락 옴"). 새 사실이 없으면 빈 배열.
            - 기록된 사실이 이번 대화에서 사실이 아니거나 달라진 것으로 드러나면, 그 정정 자체를
              새 사실로 넣어라(예: "바람 의혹은 유저의 착각으로 확인됨"). 기록은 지워지지 않고 정정으로 잇는다.
            - 원장에 남길 만큼 중요한지 애매하면 일단 넣어라. 놓친 사실은 복구할 수 없지만 사소한 사실은 해가 없다.

            기록된 사실 해석 규칙: 기록끼리 서로 모순되면 기록일이 나중인 쪽을 따르라(정정이 원본을 이긴다).

            판정 기준:
            - INSUFFICIENT: 대화에 이별·관계 정보가 거의 없어 판단 근거가 부족할 때. 억지로 확률을 내지 마라.
              이때 breakupType·partnerType·deductions는 비우고, reason에는 무엇을 더 이야기하면 좋을지
              부드러운 가이드를 담아라(예: 어쩌다 헤어졌는지, 지금 연락은 되는지, 상대와 최근 있었던 일).
            - POSSIBLE: 판단 근거가 충분한 그 외 모든 경우. 감점 항목을 채워라.
              ※ "놓아줘라"는 판정은 하지 마라. 상대가 새 사람이 있거나 신뢰가 크게 무너졌어도
                포기하라고 하지 말고, 감점을 크게 매겨 '낮은 확률'로 정직하게 보여줘라.

            감점 앵커(권장 범위, 강할수록 크게):
            - 차단/강한 거절: 25~30
            - 읽씹/무시: 15~20
            - 상대가 먼저 이별 통보: 10~15
            - 권태·성격차: 10~20
            - 오래 경과·연락 두절: 5~10
            항목별 상한은 없다. 정직하게 깎아라. 긍정 요소는 감점을 '덜' 하는 식으로만 반영하고, 점수를 직접 올리지 마라.
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

            List<DeductionItem> deductions = new ArrayList<>();
            for (JsonNode node : root.path("deductions")) {
                String signal = node.path("signal").asText("");
                int points = node.path("points").asInt(0);
                if (signal.isBlank() || points == 0) {
                    continue;
                }
                deductions.add(new DeductionItem(signal, points, node.path("evidence").asText("")));
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

            return new ReunionDiagnosis(verdict, breakupType, partnerType, deductions,
                    root.path("reason").asText(""), root.path("summary").asText(""), newFacts);
        } catch (Exception e) {
            log.error("재회 진단 JSON 파싱 실패: {}", json, e);
            throw new LlmException();
        }
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
