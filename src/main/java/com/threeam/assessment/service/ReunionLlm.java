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

            아래 JSON 스키마로만 답하라(다른 텍스트 금지):
            {
              "verdict": "POSSIBLE" | "LET_GO",
              "breakupType": "CLINGER" | "REGRETTER" | "SELF_BLAMER",
              "partnerType": "DECISIVE" | "AMBIVALENT" | "COLD",
              "deductions": [ { "signal": "짧은 신호명", "points": 정수, "evidence": "대화 속 근거" } ],
              "reason": "한두 문장 총평(반말, 다정하되 솔직하게)",
              "summary": "다음 대화에서 기억해야 할 핵심 사실 요약(바람/먼저 이별/싸움/연락상태 등)"
            }

            판정 기준:
            - LET_GO: 상대가 새 사람을 만나거나 신뢰가 회복 불가하게 무너진 경우. 확률 대신 놓아주라는 판정.
            - POSSIBLE: 그 외. 감점 항목을 채워라.

            감점 앵커(권장 범위, 강할수록 크게):
            - 차단/강한 거절: 25~30
            - 읽씹/무시: 15~20
            - 상대가 먼저 이별 통보: 10~15
            - 권태·성격차: 10~20
            - 오래 경과·연락 두절: 5~10
            항목별 상한은 없다. 정직하게 깎아라. 긍정 요소는 감점을 '덜' 하는 식으로만 반영하고, 점수를 직접 올리지 마라.
            """;

    public CompletableFuture<ReunionDiagnosis> diagnose(String memorySummary, List<ChatMessage> conversation) {
        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(ChatMessage.system(SYSTEM_PROMPT));
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

            return new ReunionDiagnosis(verdict, breakupType, partnerType, deductions,
                    root.path("reason").asText(""), root.path("summary").asText(""));
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
