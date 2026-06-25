package com.threeam.assessment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.assessment.ReadingProperties;
import com.threeam.assessment.dto.AssessmentContext;
import com.threeam.assessment.dto.ReadingDraft;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.AssessmentFactor;
import com.threeam.assessment.entity.JumpRule;
import com.threeam.assessment.entity.ReadingVocab;
import com.threeam.llm.ChatMessage;
import com.threeam.llm.LlmClient;
import com.threeam.llm.LlmException;
import com.threeam.llm.LlmJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 정밀 판독(2호출) 담당. 확정된 판정 JSON을 입력으로 받아 상대의 현재 상태를 이야기로 푼다 —
// 표지(판정 + 올린/막는 이유)와 여섯 장(상대의 지금 / 결심 / 남은 마음 / 왜 멀어졌나 /
// 막는 것 / 다시 움직일 조건), 국면. 숫자와 요인 방향은 여기서 만들지도 바꾸지도 않고,
// 요인 어휘는 유저 지면에 꺼내지 않는다(채점 내부 용어).
// 판독 지시 전문은 서비스 자산이라 소스에 두지 않고 ReadingProperties(로컬 reading.yml)로 주입받는다.
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadingLlm {

    // 판정 블록의 머리 문구. MockLlmClient가 이 문구로 판독 호출을 식별한다(개발 스텁 분기).
    public static final String VERDICT_BLOCK_HEADER =
            "판정 결과(확정 — 숫자와 요인 방향은 바꿀 수 없다. 이 판정이 왜 나왔는지를 서술하라):";

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final ReadingProperties readingProperties;

    public CompletableFuture<ReadingDraft> read(AssessmentContext context, Assessment saved) {
        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(ChatMessage.system(readingProperties.getGuide()));
        if (context.knownFactLines() != null && !context.knownFactLines().isEmpty()) {
            prompt.add(ChatMessage.system("이미 기록된 사실(괄호는 기록일):\n- "
                    + String.join("\n- ", context.knownFactLines())));
        }
        if (context.intakeBlock() != null && !context.intakeBlock().isBlank()) {
            prompt.add(ChatMessage.system(context.intakeBlock()));
        }
        prompt.addAll(context.conversation());
        // 판정 블록은 맨 끝 — 출력 직전에 확정 사실이 다시 보여야 서술이 판정을 벗어나지 않는다.
        prompt.add(ChatMessage.system(VERDICT_BLOCK_HEADER + "\n" + verdictJson(saved)));
        return llmClient.generateJsonDeep(prompt, RESPONSE_SCHEMA).thenApply(this::parse);
    }

    // 판정을 판독의 입력으로 직렬화한다. 요인 근거(evidence)까지 싣는 게 핵심 —
    // 판독은 이 증거들에 대해서만 서술해야 새 증거를 지어내지 않는다.
    private String verdictJson(Assessment saved) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("확률", saved.getProbability());
        out.put("유형", saved.getBreakupType() != null ? saved.getBreakupType().label() : null);
        out.put("유형근거", saved.getTypeEvidence());
        if (saved.getJumpRule() != null && saved.getJumpRule() != JumpRule.NONE) {
            out.put("점프", saved.getJumpRule().label());
        }
        List<Map<String, Object>> factors = new ArrayList<>();
        for (AssessmentFactor factor : saved.getFactors()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("요인", factor.getName().label());
            item.put("판정", factor.getLevel().label());
            item.put("근거", factor.getEvidence());
            if (factor.getRationale() != null) {
                item.put("사유", factor.getRationale());
            }
            factors.add(item);
        }
        out.put("요인", factors);
        if (saved.getRelapseRisk() != null) {
            out.put("유지전망", saved.getRelapseRisk().label());
        }
        out.put("판정총평", saved.getReason());
        try {
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            // 직렬화 실패는 코드 결함 — 판독 없이 진행하게 위로 던진다(판정은 이미 저장됨).
            throw new LlmException();
        }
    }

    private static Map<String, Object> sectionSchema(List<String> states) {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "state", Map.of("type", "STRING", "enum", states),
                        "answer", Map.of("type", "STRING"),
                        "reading", Map.of("type", "STRING")),
                "required", List.of("state", "answer", "reading"),
                "propertyOrdering", List.of("state", "answer", "reading"));
    }

    private static Map<String, Object> reselectSchema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "state", Map.of("type", "STRING", "enum", ReadingVocab.RESELECT_STATES),
                        "answer", Map.of("type", "STRING"),
                        "open", Map.of("type", "STRING"),
                        "route", Map.of("type", "STRING")),
                "required", List.of("state", "answer", "open", "route"),
                "propertyOrdering", List.of("state", "answer", "open", "route"));
    }

    // 판독 응답의 문법. reading.yml의 JSON 지시와 짝 — 지시를 고쳐 필드가 바뀌면 여기도 같이.
    // 전 필드 required — nullable 필드는 모델이 절차에서 빠지면 조용히 생략하는 게 실측된 자리다
    // (ReunionLlm의 matchProfile 사건과 같은 원리).
    private static final Map<String, Object> RESPONSE_SCHEMA = Map.ofEntries(
            Map.entry("type", "OBJECT"),
            Map.entry("properties", Map.ofEntries(
                    Map.entry("overall", Map.of("type", "STRING")),
                    Map.entry("coverRaise", Map.of("type", "STRING")),
                    Map.entry("coverBlock", Map.of("type", "STRING")),
                    Map.entry("now", sectionSchema(ReadingVocab.NOW_STATES)),
                    Map.entry("resolve", sectionSchema(ReadingVocab.RESOLVE_STATES)),
                    Map.entry("remain", sectionSchema(ReadingVocab.REMAIN_STATES)),
                    Map.entry("drift", Map.of("type", "STRING")),
                    Map.entry("blocking", Map.of("type", "STRING")),
                    Map.entry("reselect", reselectSchema()),
                    Map.entry("phase", Map.of("type", "STRING")),
                    Map.entry("nowTitle", Map.of("type", "STRING")),
                    Map.entry("resolveTitle", Map.of("type", "STRING")),
                    Map.entry("remainTitle", Map.of("type", "STRING")),
                    Map.entry("driftTitle", Map.of("type", "STRING")),
                    Map.entry("blockingTitle", Map.of("type", "STRING")),
                    Map.entry("routeTitle", Map.of("type", "STRING")))),
            Map.entry("required", List.of("overall", "coverRaise", "coverBlock", "now", "resolve",
                    "remain", "drift", "blocking", "reselect", "phase", "nowTitle", "resolveTitle",
                    "remainTitle", "driftTitle", "blockingTitle", "routeTitle")),
            Map.entry("propertyOrdering", List.of("overall", "coverRaise", "coverBlock", "now",
                    "resolve", "remain", "drift", "blocking", "reselect", "phase", "nowTitle",
                    "resolveTitle", "remainTitle", "driftTitle", "blockingTitle", "routeTitle")));

    // 화면 폴백과 동일한 고정 장 제목 — 모델이 제목을 비워 보내도 판독을 살린다.
    private static final Map<String, String> DEFAULT_TITLES = Map.of(
            "now", "상대는 지금 무슨 생각일까",
            "resolve", "정말 끝낼 결심이었을까",
            "remain", "마음은 남아 있을까",
            "drift", "왜 멀어졌을까",
            "blocking", "지금 막고 있는 것은",
            "route", "무엇이 바뀌면 다시 움직일까");

    private ReadingDraft parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(LlmJson.salvage(json));
            ReadingDraft.Section now = section(root.path("now"), ReadingVocab.NOW_STATES, "MIXED");
            ReadingDraft.Section resolve =
                    section(root.path("resolve"), ReadingVocab.RESOLVE_STATES, "UNSTABLE");
            ReadingDraft.Section remain =
                    section(root.path("remain"), ReadingVocab.REMAIN_STATES, "LITTLE_EVIDENCE");
            JsonNode reselectNode = root.path("reselect");
            ReadingDraft.Reselect reselect = new ReadingDraft.Reselect(
                    state(reselectNode, ReadingVocab.RESELECT_STATES, "CONDITIONAL"),
                    clip(requireText(reselectNode, "answer"), ANSWER_MAX),
                    requireText(reselectNode, "open"),
                    requireText(reselectNode, "route"));

            Map<String, String> titles = new LinkedHashMap<>();
            for (String key : ReadingVocab.CHAPTER_KEYS) {
                titles.put(key, title(root, key + "Title", key));
            }

            return new ReadingDraft(
                    requireText(root, "overall"),
                    clip(requireText(root, "coverRaise"), ANSWER_MAX),
                    clip(requireText(root, "coverBlock"), ANSWER_MAX),
                    now, resolve, remain,
                    requireText(root, "drift"),
                    requireText(root, "blocking"),
                    reselect,
                    clip(requireText(root, "phase"), ANSWER_MAX),
                    titles);
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            // 본문에는 사연 기반 서술이 들어 있어 개인정보다 — 원문 전체는 남기지 않는다.
            log.error("정밀 판독 JSON 파싱 실패 (본문 길이 {}자)", json == null ? 0 : json.length(), e);
            throw new LlmException();
        }
    }

    private ReadingDraft.Section section(JsonNode node, List<String> states, String fallbackState) {
        return new ReadingDraft.Section(
                state(node, states, fallbackState),
                clip(requireText(node, "answer"), ANSWER_MAX),
                requireText(node, "reading"));
    }

    // 스키마가 enum을 강제하지만 salvage 경로(잘린 응답 복구)는 뚫릴 수 있어 한 번 더 거른다.
    private String state(JsonNode node, List<String> states, String fallback) {
        String value = node.path("state").asText("").trim();
        if (states.contains(value)) {
            return value;
        }
        log.warn("판독 state 폐기(사전에 없음): {} — {}로 대체", value, fallback);
        return fallback;
    }

    // 답과 서술이 비면 판독으로서 성립하지 않는다 — 판정은 이미 저장됐으니 판독만 실패시킨다.
    private String requireText(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) {
            log.warn("정밀 판독 필수 필드 누락: {}", field);
            throw new LlmException();
        }
        return value;
    }

    private String title(JsonNode root, String field, String key) {
        String value = root.path(field).asText("").trim();
        return value.isEmpty() ? DEFAULT_TITLES.get(key) : clip(value, TITLE_MAX);
    }

    private static final int ANSWER_MAX = 300;
    private static final int TITLE_MAX = 100;

    private String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
