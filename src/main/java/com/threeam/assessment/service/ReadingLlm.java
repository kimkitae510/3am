package com.threeam.assessment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.assessment.ReadingProperties;
import com.threeam.assessment.dto.AssessmentContext;
import com.threeam.assessment.dto.ReadingDraft;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.AssessmentFactor;
import com.threeam.assessment.entity.FactorName;
import com.threeam.assessment.entity.JumpRule;
import com.threeam.assessment.entity.ReadingVocab;
import com.threeam.llm.ChatMessage;
import com.threeam.llm.LlmClient;
import com.threeam.llm.LlmException;
import com.threeam.llm.LlmJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 정밀 판독(2호출) 담당. 확정된 판정 JSON을 입력으로 받아 "왜 이 판정인지"를 네 질문
// (상대의 지금 / 결심 강도 / 남은 마음 / 재선택)의 답과 증거로 서술한다.
// 숫자와 요인 방향은 여기서 만들지도 바꾸지도 않는다 — 채점은 rubric(1호출) + TypeBandScorer 소관.
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
                        "closed", Map.of("type", "STRING"),
                        "open", Map.of("type", "STRING"),
                        "route", Map.of("type", "STRING")),
                "required", List.of("state", "answer", "closed", "open", "route"),
                "propertyOrdering", List.of("state", "answer", "closed", "open", "route"));
    }

    private static Map<String, Object> evidenceSchema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "question", Map.of("type", "STRING", "enum", ReadingVocab.QUESTIONS),
                        "source", Map.of("type", "STRING", "enum", ReadingVocab.SOURCES),
                        "name", Map.of("type", "STRING"),
                        "direction", Map.of("type", "STRING", "enum", ReadingVocab.DIRECTIONS),
                        "fact", Map.of("type", "STRING"),
                        "interpretation", Map.of("type", "STRING")),
                "required", List.of("question", "source", "name", "direction", "fact", "interpretation"),
                "propertyOrdering", List.of("question", "source", "name", "direction", "fact",
                        "interpretation"));
    }

    // 판독 응답의 문법. reading.yml의 JSON 지시와 짝 — 지시를 고쳐 필드가 바뀌면 여기도 같이.
    // 전 필드 required — nullable 필드는 모델이 절차에서 빠지면 조용히 생략하는 게 실측된 자리다
    // (ReunionLlm의 matchProfile 사건과 같은 원리).
    private static final Map<String, Object> RESPONSE_SCHEMA = Map.ofEntries(
            Map.entry("type", "OBJECT"),
            Map.entry("properties", Map.ofEntries(
                    Map.entry("overall", Map.of("type", "STRING")),
                    Map.entry("narrative", Map.of("type", "STRING")),
                    Map.entry("now", sectionSchema(ReadingVocab.NOW_STATES)),
                    Map.entry("resolve", sectionSchema(ReadingVocab.RESOLVE_STATES)),
                    Map.entry("remain", sectionSchema(ReadingVocab.REMAIN_STATES)),
                    Map.entry("reselect", reselectSchema()),
                    Map.entry("evidence", Map.of("type", "ARRAY", "items", evidenceSchema())),
                    Map.entry("phase", Map.of("type", "STRING")),
                    Map.entry("narrativeTitle", Map.of("type", "STRING")),
                    Map.entry("nowTitle", Map.of("type", "STRING")),
                    Map.entry("resolveRemainTitle", Map.of("type", "STRING")),
                    Map.entry("reselectTitle", Map.of("type", "STRING")))),
            Map.entry("required", List.of("overall", "narrative", "now", "resolve", "remain",
                    "reselect", "evidence", "phase", "narrativeTitle", "nowTitle",
                    "resolveRemainTitle", "reselectTitle")),
            Map.entry("propertyOrdering", List.of("overall", "narrative", "now", "resolve",
                    "remain", "reselect", "evidence", "phase", "narrativeTitle", "nowTitle",
                    "resolveRemainTitle", "reselectTitle")));

    // 화면 폴백과 동일한 고정 장 제목 — 모델이 제목을 비워 보내도 판독을 살린다.
    private static final Map<String, String> DEFAULT_TITLES = Map.of(
            "narrative", "관계가 뒤집힌 순간",
            "now", "상대는 지금 어떤 상태인가",
            "resolveRemain", "결심은 진짜인가, 마음은 남았는가",
            "reselect", "다시 선택할 가능성은");

    private ReadingDraft parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(LlmJson.salvage(json));
            String overall = requireText(root, "overall");
            String narrative = requireText(root, "narrative");
            ReadingDraft.Section now = section(root.path("now"), ReadingVocab.NOW_STATES, "MIXED");
            ReadingDraft.Section resolve =
                    section(root.path("resolve"), ReadingVocab.RESOLVE_STATES, "UNSTABLE");
            ReadingDraft.Section remain =
                    section(root.path("remain"), ReadingVocab.REMAIN_STATES, "LITTLE_EVIDENCE");
            ReadingDraft.Reselect reselect = reselect(root.path("reselect"));
            String phase = clip(requireText(root, "phase"), ANSWER_MAX);

            Map<String, String> titles = new LinkedHashMap<>();
            titles.put("narrative", title(root, "narrativeTitle", "narrative"));
            titles.put("now", title(root, "nowTitle", "now"));
            titles.put("resolveRemain", title(root, "resolveRemainTitle", "resolveRemain"));
            titles.put("reselect", title(root, "reselectTitle", "reselect"));

            return new ReadingDraft(overall, narrative, now, resolve, remain, reselect,
                    parseEvidence(root), phase, titles);
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

    private ReadingDraft.Reselect reselect(JsonNode node) {
        return new ReadingDraft.Reselect(
                state(node, ReadingVocab.RESELECT_STATES, "CONDITIONAL"),
                clip(requireText(node, "answer"), ANSWER_MAX),
                requireText(node, "closed"),
                requireText(node, "open"),
                requireText(node, "route"));
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

    // 증거 폭주 안전핀. 질문 4개에 요인 7 + 추가신호 3이 전부 걸려도 넉넉한 값이다.
    private static final int EVIDENCE_MAX = 28;

    // 추가신호 상한 — 다다익선으로 풀면 잡음 카드가 판독을 희석한다(설계 확정값).
    private static final int EXTRA_SIGNAL_MAX = 3;

    private List<ReadingDraft.Evidence> parseEvidence(JsonNode root) {
        List<ReadingDraft.Evidence> out = new ArrayList<>();
        Set<String> extraNames = new LinkedHashSet<>();
        for (JsonNode node : root.path("evidence")) {
            if (out.size() >= EVIDENCE_MAX) {
                break;
            }
            String question = node.path("question").asText("").trim();
            String source = node.path("source").asText("").trim();
            String direction = node.path("direction").asText("").trim();
            String name = clip(node.path("name").asText("").trim(), NAME_MAX);
            String fact = clip(node.path("fact").asText("").trim(), EVIDENCE_TEXT_MAX);
            String interpretation =
                    clip(node.path("interpretation").asText("").trim(), EVIDENCE_TEXT_MAX);
            if (!ReadingVocab.QUESTIONS.contains(question) || !ReadingVocab.SOURCES.contains(source)
                    || !ReadingVocab.DIRECTIONS.contains(direction)
                    || name.isBlank() || fact.isBlank() || interpretation.isBlank()) {
                log.warn("판독 증거 폐기(어휘 밖 또는 빈 값): question={} source={}", question, source);
                continue;
            }
            if ("요인".equals(source)) {
                // 요인 증거는 채점된 7슬롯의 재소환이어야 한다 — 가짜 요인 이름 차단.
                if (FactorName.fromLabel(name) == null) {
                    log.warn("판독 증거 폐기(요인 사전에 없음): {}", name);
                    continue;
                }
            } else {
                // 추가신호는 이름 기준 최대 3개 — 단일 행동에 새 이름을 붙여 늘리는 걸 막는다.
                if (!extraNames.contains(name) && extraNames.size() >= EXTRA_SIGNAL_MAX) {
                    log.warn("판독 추가신호 폐기(상한 초과): {}", name);
                    continue;
                }
                extraNames.add(name);
            }
            out.add(new ReadingDraft.Evidence(question, source, name, direction, fact,
                    interpretation));
        }
        return out;
    }

    private static final int ANSWER_MAX = 300;
    private static final int TITLE_MAX = 100;
    private static final int NAME_MAX = 30;
    private static final int EVIDENCE_TEXT_MAX = 500;

    private String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
