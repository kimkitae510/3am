package com.threeam.assessment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.assessment.ReadingProperties;
import com.threeam.assessment.dto.ReadingDraft;
import com.threeam.assessment.dto.ReunionDiagnosis;
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

// 정밀 판독(2호출) 담당 — v11.1 계약.
// 진단 문장은 1호출이 확정한다. 판독은 그걸 복사만 하고, 서버는 아예 입력값으로 되돌려
// 병합한다(복사도 안 믿는다 — 모델이 한 글자라도 고치면 화면과 판정이 갈린다).
// 판독이 실제로 쓰는 것은 심층 장, 행동 계획, 후속 칩이다.
// 입력(ReadingPacket)에 요인표, 점프, 관계심리 판정값은 싣지 않는다 — 전부 넘기면
// 2호출이 요인표를 자연어로 복창하는 경향이 실측됐다.
// 판독 지시 전문은 서비스 자산이라 소스에 두지 않고 ReadingProperties(로컬 reading.yml)로 주입받는다.
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadingLlm {

    // packet 블록의 머리 문구. MockLlmClient가 이 문구로 판독 호출을 식별한다(개발 스텁 분기).
    public static final String PAYLOAD_HEADER =
            "확정 판정 ReadingPacket(이번 리포트의 유일한 사례 데이터 — 여기 없는 사실을 만들지 마라):";

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final ReadingProperties readingProperties;

    public CompletableFuture<ReadingDraft> read(Assessment saved, ReunionDiagnosis diagnosis,
                                                String intakeBlock, String level,
                                                List<DiagnosisCard> cards) {
        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(ChatMessage.system(readingProperties.getGuide()));
        // packet은 user 턴으로 보낸다 — system만 보내면 전부 systemInstruction으로 빠져
        // contents가 비고, Gemini가 400(contents field is required)으로 거절한다(실측).
        prompt.add(ChatMessage.user(PAYLOAD_HEADER + "\n"
                + packetJson(saved, diagnosis, intakeBlock, level, cards)));
        return llmClient.generateJsonDeep(prompt, RESPONSE_SCHEMA)
                .thenApply(json -> parse(json, diagnosis, cards));
    }

    // 화면에 나갈 진단 카드 하나 — 1호출이 쓴 문장(headline/reading)에 백엔드가 순위와
    // 등급, 묶음, 근거 상태를 붙인 것. 판독 입력과 최종 저장에 같은 값이 쓰인다.
    public record DiagnosisCard(String key, String label, String group, int rank, String level,
                                String evidenceState, String headline, String reading,
                                List<String> factIds) {
    }

    private String packetJson(Assessment saved, ReunionDiagnosis diagnosis, String intakeBlock,
                              String level, List<DiagnosisCard> cards) {
        List<ReunionDiagnosis.ReadingFact> facts = diagnosis.readingFacts();
        if (facts == null || facts.isEmpty()) {
            // 루브릭이 관찰 사실을 아직 안 내는 동안의 안전망 — 요인 근거를 사실로 승격한다.
            facts = fallbackFacts(saved);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("probability", saved.getProbability());
        out.put("level", level);
        // 누가 이별을 통보했는가. 이게 빠지면 판독이 관찰 사실에서 역할을 역추정하다
        // 두 사람을 뒤집는다(실측: 유저가 통보한 판을 "상대가 이별을 결심한 이유"로 씀).
        String declaredBy = declaredBy(saved, diagnosis);
        if (declaredBy != null) {
            out.put("breakupDeclaredBy", declaredBy);
        }
        if (intakeBlock != null && !intakeBlock.isBlank()) {
            out.put("intake", intakeBlock);
        }
        if (diagnosis.displayDiagnosis() != null) {
            out.put("diagnosisSummary", diagnosis.displayDiagnosis().summary());
        }
        if (diagnosis.timeEffect() != null) {
            ReunionDiagnosis.TimeEffect effect = diagnosis.timeEffect();
            Map<String, Object> time = new LinkedHashMap<>();
            time.put("state", effect.state());
            time.put("horizon", effect.horizon());
            time.put("actionBias", effect.actionBias());
            time.put("reason", effect.reason());
            out.put("timeEffect", time);
        }

        List<Map<String, Object>> cardRows = new ArrayList<>();
        for (DiagnosisCard card : cards) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", card.key());
            row.put("label", card.label());
            row.put("group", card.group());
            row.put("rank", card.rank());
            row.put("level", card.level());
            row.put("evidenceState", card.evidenceState());
            row.put("headline", card.headline());
            row.put("reading", card.reading());
            row.put("factIds", card.factIds());
            cardRows.add(row);
        }
        out.put("diagnosisItems", cardRows);

        List<Map<String, Object>> factRows = new ArrayList<>();
        int order = 1;
        for (ReunionDiagnosis.ReadingFact fact : facts) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", fact.id());
            row.put("order", order++);
            row.put("actor", fact.actor());
            row.put("kind", fact.kind());
            row.put("fact", fact.fact());
            if (fact.quote() != null) {
                row.put("quote", fact.quote());
            }
            if (fact.timing() != null) {
                row.put("timing", fact.timing());
            }
            factRows.add(row);
        }
        out.put("readingFacts", factRows);

        List<Map<String, String>> questions = new ArrayList<>();
        if (diagnosis.directQuestions() != null) {
            int qNo = 1;
            for (String question : diagnosis.directQuestions()) {
                questions.add(Map.of("id", String.format("Q%02d", qNo++), "question", question));
            }
        }
        out.put("directQuestions", questions);

        List<Map<String, String>> interpretations = new ArrayList<>();
        if (diagnosis.userFocus() != null) {
            int uNo = 1;
            for (ReunionDiagnosis.FocusItem item : diagnosis.userFocus()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("id", String.format("U%02d", uNo++));
                if (item.factId() != null) {
                    row.put("factId", item.factId());
                }
                row.put("interpretation", item.interpretation());
                interpretations.add(row);
            }
        }
        out.put("userInterpretations", interpretations);

        List<String> watch = new ArrayList<>();
        saved.getWatchPoints().forEach(w -> watch.add(w.getPoint() + " — " + w.getEffect()));
        out.put("watchFor", watch);
        try {
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            // 직렬화 실패는 코드 결함 — 판독 없이 진행하게 위로 던진다(판정은 이미 저장됨).
            throw new LlmException();
        }
    }

    // 통보자는 매칭 분류(1호출)가 이미 가려둔 값이다. 없으면 점프 규칙에서 읽는다 —
    // 유저 통보 계열 점프는 이름 자체가 "유저가 통보했다"를 전제로 발동한다.
    private String declaredBy(Assessment saved, ReunionDiagnosis diagnosis) {
        if (diagnosis.matchProfile() != null && diagnosis.matchProfile().dumper() != null
                && !"미상".equals(diagnosis.matchProfile().dumper())) {
            return diagnosis.matchProfile().dumper();
        }
        JumpRule jump = saved.getJumpRule();
        if (jump != null && jump.label().startsWith("유저통보")) {
            return "나";
        }
        return null;
    }

    private List<ReunionDiagnosis.ReadingFact> fallbackFacts(Assessment saved) {
        List<ReunionDiagnosis.ReadingFact> out = new ArrayList<>();
        for (AssessmentFactor factor : saved.getFactors()) {
            String evidence = factor.getEvidence();
            if (evidence == null || evidence.isBlank() || ReunionLlm.NO_EVIDENCE.equals(evidence)) {
                continue;
            }
            out.add(new ReunionDiagnosis.ReadingFact(
                    String.format("F%02d", out.size() + 1), "CONTEXT", "ACTION",
                    evidence, null, null));
        }
        return out;
    }

    private static Map<String, Object> psychologySchema() {
        return Map.of(
                "type", "OBJECT",
                "nullable", true,
                "properties", Map.of(
                        "concept", Map.of("type", "STRING"),
                        "reading", Map.of("type", "STRING")),
                "required", List.of("concept", "reading"),
                "propertyOrdering", List.of("concept", "reading"));
    }

    private static Map<String, Object> chapterSchema() {
        return Map.ofEntries(
                Map.entry("type", "OBJECT"),
                Map.entry("properties", Map.ofEntries(
                        Map.entry("eyebrow", Map.of("type", "STRING", "nullable", true)),
                        Map.entry("title", Map.of("type", "STRING")),
                        Map.entry("chapterRole", Map.of("type", "STRING",
                                "enum", ReadingVocab.CHAPTER_ROLES)),
                        Map.entry("interpretationId", Map.of("type", "STRING", "nullable", true)),
                        Map.entry("answer", Map.of("type", "STRING")),
                        Map.entry("reading", Map.of("type", "STRING")),
                        Map.entry("psychology", psychologySchema()),
                        Map.entry("repairPrinciple", Map.of("type", "STRING", "nullable", true)),
                        Map.entry("evidenceIds", Map.of("type", "ARRAY",
                                "items", Map.of("type", "STRING"))))),
                Map.entry("required", List.of("title", "chapterRole", "answer", "reading")),
                Map.entry("propertyOrdering", List.of("eyebrow", "title", "chapterRole",
                        "interpretationId", "answer", "reading", "psychology", "repairPrinciple",
                        "evidenceIds")));
    }

    // 진단 배열은 스키마에 두되 내용은 안 믿는다 — 서버가 입력값으로 덮는다.
    // 그래도 요구하는 이유: 모델이 진단을 읽고 쓰는 절차를 밟아야 심층 장이 진단과
    // 어긋나지 않는다(읽지도 않고 쓰면 같은 리포트가 두 판을 말한다).
    private static Map<String, Object> diagnosisEchoSchema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "key", Map.of("type", "STRING", "enum", ReadingVocab.DIAGNOSIS_KEYS),
                        "label", Map.of("type", "STRING"),
                        "group", Map.of("type", "STRING", "enum", ReadingVocab.GROUPS),
                        "rank", Map.of("type", "INTEGER"),
                        "level", Map.of("type", "STRING", "enum", ReadingVocab.LEVELS),
                        "evidenceState", Map.of("type", "STRING",
                                "enum", ReadingVocab.EVIDENCE_STATES),
                        "headline", Map.of("type", "STRING"),
                        "reading", Map.of("type", "STRING"),
                        "factIds", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))),
                "required", List.of("key", "headline", "reading"),
                "propertyOrdering", List.of("key", "label", "group", "rank", "level",
                        "evidenceState", "headline", "reading", "factIds"));
    }

    private static Map<String, Object> actionPlanSchema() {
        return Map.ofEntries(
                Map.entry("type", "OBJECT"),
                Map.entry("properties", Map.ofEntries(
                        Map.entry("title", Map.of("type", "STRING")),
                        Map.entry("stance", Map.of("type", "STRING", "enum", ReadingVocab.STANCES)),
                        Map.entry("answer", Map.of("type", "STRING")),
                        Map.entry("timing", Map.of("type", "STRING")),
                        Map.entry("whyThisTiming", Map.of("type", "STRING")),
                        Map.entry("goal", Map.of("type", "STRING")),
                        Map.entry("do", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))),
                        Map.entry("stopCondition", Map.of("type", "STRING")),
                        Map.entry("avoid", Map.of("type", "ARRAY",
                                "items", Map.of("type", "STRING"))))),
                Map.entry("required", List.of("title", "stance", "answer", "timing",
                        "whyThisTiming", "goal", "do", "stopCondition", "avoid")),
                Map.entry("propertyOrdering", List.of("title", "stance", "answer", "timing",
                        "whyThisTiming", "goal", "do", "stopCondition", "avoid")));
    }

    private static Map<String, Object> internalSchema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "nowState", Map.of("type", "STRING", "enum", ReadingVocab.NOW_STATES),
                        "resolveState", Map.of("type", "STRING", "enum", ReadingVocab.RESOLVE_STATES),
                        "remainState", Map.of("type", "STRING", "enum", ReadingVocab.REMAIN_STATES),
                        "reselectState", Map.of("type", "STRING", "enum", ReadingVocab.RESELECT_STATES)),
                "required", List.of("nowState", "resolveState", "remainState", "reselectState"),
                "propertyOrdering", List.of("nowState", "resolveState", "remainState", "reselectState"));
    }

    // 판독 응답의 문법. reading.yml(v11.1)의 출력 형식과 짝 — 지시를 고쳐 필드가 바뀌면 여기도 같이.
    private static final Map<String, Object> RESPONSE_SCHEMA = Map.ofEntries(
            Map.entry("type", "OBJECT"),
            Map.entry("properties", Map.ofEntries(
                    Map.entry("diagnosisSummary", Map.of("type", "STRING")),
                    Map.entry("diagnosis", Map.of("type", "ARRAY", "items", diagnosisEchoSchema())),
                    Map.entry("analysisSection", Map.of(
                            "type", "OBJECT",
                            "properties", Map.of("title", Map.of("type", "STRING")),
                            "required", List.of("title"),
                            "propertyOrdering", List.of("title"))),
                    Map.entry("analysisChapters", Map.of("type", "ARRAY", "items", chapterSchema())),
                    Map.entry("actionPlan", actionPlanSchema()),
                    Map.entry("chipSeeds", Map.of("type", "ARRAY",
                            "items", Map.of("type", "STRING"))),
                    Map.entry("internal", internalSchema()))),
            Map.entry("required", List.of("diagnosisSummary", "diagnosis", "analysisSection",
                    "analysisChapters", "actionPlan", "chipSeeds", "internal")),
            Map.entry("propertyOrdering", List.of("diagnosisSummary", "diagnosis",
                    "analysisSection", "analysisChapters", "actionPlan", "chipSeeds", "internal")));

    // 개수 상한 — 지시(심층 장 2~4, 칩 2~4)의 안전핀.
    private static final int CHAPTER_MAX = 4;
    private static final int CHIP_MAX = 4;
    private static final int LIST_TEXT_MAX = 300;

    private ReadingDraft parse(String json, ReunionDiagnosis diagnosis,
                               List<DiagnosisCard> cards) {
        try {
            JsonNode root = objectMapper.readTree(LlmJson.salvage(json));

            List<ReadingDraft.Chapter> chapters = new ArrayList<>();
            for (JsonNode node : root.path("analysisChapters")) {
                if (chapters.size() >= CHAPTER_MAX) {
                    break;
                }
                String title = node.path("title").asText("").trim();
                String answer = node.path("answer").asText("").trim();
                String reading = node.path("reading").asText("").trim();
                if (title.isBlank() || answer.isBlank() || reading.isBlank()) {
                    continue;
                }
                String role = node.path("chapterRole").asText("").trim();
                String principle = node.path("repairPrinciple").asText("").trim();
                String interpretationId = node.path("interpretationId").asText("").trim();
                chapters.add(new ReadingDraft.Chapter(
                        clip(node.path("eyebrow").asText("").trim(), LIST_TEXT_MAX),
                        clip(title, LIST_TEXT_MAX),
                        ReadingVocab.CHAPTER_ROLES.contains(role) ? role : "CORE_CONTRADICTION",
                        interpretationId.isBlank() ? null : clip(interpretationId, 10),
                        clip(answer, 500), reading,
                        psychology(node.path("psychology")),
                        principle.isBlank() ? null : clip(principle, 500),
                        strings(node.path("evidenceIds"), 10)));
            }

            // 진단 문장은 1호출 값을 그대로 쓴다 — 모델이 복사하며 고쳐 쓸 여지를 없앤다.
            List<ReadingDraft.Diagnosis> merged = new ArrayList<>();
            for (DiagnosisCard card : cards) {
                merged.add(new ReadingDraft.Diagnosis(card.key(), card.label(), card.group(),
                        card.rank(), card.level(), card.evidenceState(), card.headline(),
                        card.reading(), card.factIds()));
            }
            // 진단이 하나도 없으면 v11.1 리포트가 아니다 — 판독 실패로 처리한다(판정은 유지).
            if (merged.isEmpty()) {
                log.warn("화면 진단 카드가 없음 — 판독 실패 처리");
                throw new LlmException();
            }

            JsonNode planNode = root.path("actionPlan");
            String stance = planNode.path("stance").asText("").trim();
            ReadingDraft.ActionPlan plan = new ReadingDraft.ActionPlan(
                    clip(requireText(planNode, "title"), LIST_TEXT_MAX),
                    ReadingVocab.STANCES.contains(stance) ? stance : "HOLD_AND_REASSESS",
                    clip(requireText(planNode, "answer"), 500),
                    clip(requireText(planNode, "timing"), LIST_TEXT_MAX),
                    clip(planNode.path("whyThisTiming").asText("").trim(), 500),
                    clip(planNode.path("goal").asText("").trim(), LIST_TEXT_MAX),
                    strings(planNode.path("do"), 4),
                    clip(planNode.path("stopCondition").asText("").trim(), LIST_TEXT_MAX),
                    strings(planNode.path("avoid"), 4));

            JsonNode internal = root.path("internal");
            ReadingDraft.Internal states = new ReadingDraft.Internal(
                    state(internal, "nowState", ReadingVocab.NOW_STATES, "MIXED"),
                    state(internal, "resolveState", ReadingVocab.RESOLVE_STATES, "UNSTABLE"),
                    state(internal, "remainState", ReadingVocab.REMAIN_STATES, "LITTLE_EVIDENCE"),
                    state(internal, "reselectState", ReadingVocab.RESELECT_STATES, "CONDITIONAL"));

            ReunionDiagnosis.DisplayDiagnosis display = diagnosis.displayDiagnosis();
            return new ReadingDraft(
                    display != null ? display.summary() : "",
                    display != null ? display.timeInsight() : null,
                    merged,
                    clip(root.path("analysisSection").path("title").asText("").trim(),
                            LIST_TEXT_MAX),
                    chapters, plan,
                    strings(root.path("chipSeeds"), CHIP_MAX),
                    states);
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            // 본문에는 사연 기반 서술이 들어 있어 개인정보다 — 원문 전체는 남기지 않는다.
            log.error("정밀 판독 JSON 파싱 실패 (본문 길이 {}자)", json == null ? 0 : json.length(), e);
            throw new LlmException();
        }
    }

    private ReadingDraft.Psychology psychology(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        String concept = node.path("concept").asText("").trim();
        String reading = node.path("reading").asText("").trim();
        if (concept.isBlank() || reading.isBlank()) {
            return null;
        }
        return new ReadingDraft.Psychology(clip(concept, 100), reading);
    }

    private List<String> strings(JsonNode array, int max) {
        List<String> out = new ArrayList<>();
        for (JsonNode node : array) {
            String value = node.asText("").trim();
            if (value.isBlank() || out.size() >= max) {
                continue;
            }
            out.add(clip(value, LIST_TEXT_MAX));
        }
        return out;
    }

    // 스키마가 enum을 강제하지만 salvage 경로(잘린 응답 복구)는 뚫릴 수 있어 한 번 더 거른다.
    private String state(JsonNode node, String field, List<String> allowed, String fallback) {
        String value = node.path(field).asText("").trim();
        if (allowed.contains(value)) {
            return value;
        }
        log.warn("판독 state 폐기(사전에 없음): {}={} — {}로 대체", field, value, fallback);
        return fallback;
    }

    // 행동 계획의 뼈대가 비면 리포트로 성립하지 않는다 — 판독만 실패시킨다(판정은 유지).
    private String requireText(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) {
            log.warn("정밀 판독 필수 필드 누락: {}", field);
            throw new LlmException();
        }
        return value;
    }

    private String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
