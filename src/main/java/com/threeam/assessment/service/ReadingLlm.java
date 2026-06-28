package com.threeam.assessment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.assessment.ReadingProperties;
import com.threeam.assessment.dto.ReadingDraft;
import com.threeam.assessment.dto.ReunionDiagnosis;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.AssessmentFactor;
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

// 정밀 판독(2호출) 담당 — 스토리북 v4 계약.
// 입력(ReadingPacket)은 일부러 얇다: 확률, 등급, 유형(내부 앵커), 대표 근거(primaryDriver),
// 관찰 사실(readingFacts), 유저 질문/해석, 관찰 포인트. 요인표, 점프, 관계심리, 유지전망
// 판정값은 싣지 않는다 — 전부 넘기면 2호출이 요인표를 자연어로 복창하는 경향이 실측됐고,
// 관계심리는 2호출이 사실을 보고 직접 고르는 것이 계약이다(reading.yml).
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
                                                TypeBandScorer.Driver primaryDriver) {
        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(ChatMessage.system(readingProperties.getGuide()));
        // packet은 user 턴으로 보낸다 — system만 보내면 전부 systemInstruction으로 빠져
        // contents가 비고, Gemini가 400(contents field is required)으로 거절한다(실측).
        prompt.add(ChatMessage.user(PAYLOAD_HEADER + "\n"
                + packetJson(saved, diagnosis, intakeBlock, level, primaryDriver)));
        return llmClient.generateJsonDeep(prompt, RESPONSE_SCHEMA).thenApply(this::parse);
    }

    private String packetJson(Assessment saved, ReunionDiagnosis diagnosis, String intakeBlock,
                              String level, TypeBandScorer.Driver primaryDriver) {
        List<ReunionDiagnosis.ReadingFact> facts = diagnosis.readingFacts();
        if (facts == null || facts.isEmpty()) {
            // 루브릭이 관찰 사실을 아직 안 내는 동안의 안전망 — 요인 근거를 사실로 승격한다.
            facts = fallbackFacts(saved);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("probability", saved.getProbability());
        out.put("level", level);
        out.put("breakupType", saved.getBreakupType() != null ? saved.getBreakupType().label() : null);
        if (primaryDriver != null) {
            Map<String, Object> primary = new LinkedHashMap<>();
            primary.put("direction", primaryDriver.direction());
            primary.put("factIds", matchFactIds(primaryDriver, facts));
            primary.put("meaning", primaryDriver.rationale() == null || primaryDriver.rationale().isBlank()
                    ? primaryDriver.evidence() : primaryDriver.rationale());
            out.put("primaryDriver", primary);
        }
        // 인테이크(나이, 기간, 경과)는 판정값이 아니라 사실이라 복창 위험이 없다 —
        // 루브릭 추출이 INTAKE_ANSWER를 빠뜨려도 기간 없는 리포트가 되지 않게 싣는다.
        if (intakeBlock != null && !intakeBlock.isBlank()) {
            out.put("intake", intakeBlock);
        }

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
        out.put("directQuestions",
                diagnosis.directQuestions() == null ? List.of() : diagnosis.directQuestions());
        List<Map<String, String>> interpretations = new ArrayList<>();
        if (diagnosis.userFocus() != null) {
            for (ReunionDiagnosis.FocusItem item : diagnosis.userFocus()) {
                Map<String, String> row = new LinkedHashMap<>();
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

    // primaryDriver의 근거 문장과 겹치는 관찰 사실을 잇는다(최선 노력) — 못 찾으면 빈 목록.
    // 프롬프트가 meaning으로도 풀 수 있어 연결 실패가 치명적이지 않다.
    private List<String> matchFactIds(TypeBandScorer.Driver driver,
                                      List<ReunionDiagnosis.ReadingFact> facts) {
        String evidence = driver.evidence();
        if (evidence == null || evidence.isBlank()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (ReunionDiagnosis.ReadingFact fact : facts) {
            if (ids.size() >= 2) {
                break;
            }
            if (fact.fact().contains(evidence) || evidence.contains(fact.fact())
                    || (fact.quote() != null && evidence.contains(fact.quote()))) {
                ids.add(fact.id());
            }
        }
        return ids;
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
                        Map.entry("eyebrow", Map.of("type", "STRING")),
                        Map.entry("title", Map.of("type", "STRING")),
                        Map.entry("chapterRole", Map.of("type", "STRING",
                                "enum", ReadingVocab.CHAPTER_ROLES)),
                        Map.entry("answer", Map.of("type", "STRING")),
                        Map.entry("reading", Map.of("type", "STRING")),
                        Map.entry("psychology", psychologySchema()),
                        Map.entry("repairPrinciple", Map.of("type", "STRING", "nullable", true)),
                        Map.entry("evidenceIds", Map.of("type", "ARRAY",
                                "items", Map.of("type", "STRING"))))),
                Map.entry("required", List.of("eyebrow", "title", "chapterRole", "answer",
                        "reading", "evidenceIds")),
                Map.entry("propertyOrdering", List.of("eyebrow", "title", "chapterRole", "answer",
                        "reading", "psychology", "repairPrinciple", "evidenceIds")));
    }

    private static Map<String, Object> barrierSchema() {
        return Map.of(
                "type", "OBJECT",
                "nullable", true,
                "properties", Map.of(
                        "answer", Map.of("type", "STRING"),
                        "reading", Map.of("type", "STRING"),
                        "evidenceIds", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))),
                "required", List.of("answer", "reading"),
                "propertyOrdering", List.of("answer", "reading", "evidenceIds"));
    }

    private static Map<String, Object> maintenanceSchema() {
        return Map.of(
                "type", "OBJECT",
                "nullable", true,
                "properties", Map.of(
                        "title", Map.of("type", "STRING"),
                        "answer", Map.of("type", "STRING"),
                        "psychology", psychologySchema(),
                        "reading", Map.of("type", "STRING"),
                        "repairPrinciple", Map.of("type", "STRING")),
                "required", List.of("title", "answer", "reading", "repairPrinciple"),
                "propertyOrdering", List.of("title", "answer", "psychology", "reading",
                        "repairPrinciple"));
    }

    private static Map<String, Object> reselectSchema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "title", Map.of("type", "STRING"),
                        "answer", Map.of("type", "STRING"),
                        "reading", Map.of("type", "STRING"),
                        "turningPoints", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))),
                "required", List.of("title", "answer", "reading", "turningPoints"),
                "propertyOrdering", List.of("title", "answer", "reading", "turningPoints"));
    }

    private static Map<String, Object> finalSchema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "stateLabel", Map.of("type", "STRING"),
                        "chipSeeds", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))),
                "required", List.of("stateLabel", "chipSeeds"),
                "propertyOrdering", List.of("stateLabel", "chipSeeds"));
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

    private static Map<String, Object> probabilityReadingSchema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "reading", Map.of("type", "STRING"),
                        "evidenceIds", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))),
                "required", List.of("reading", "evidenceIds"),
                "propertyOrdering", List.of("reading", "evidenceIds"));
    }

    // 판독 응답의 문법. reading.yml(v4)의 출력 형식과 짝 — 지시를 고쳐 필드가 바뀌면 여기도 같이.
    // nullable은 정말 없을 수 있는 것만(장벽, 유지 인사이트, 장 안의 심리/원리) — 탈출구 없는
    // nullable은 조용한 생략 사고가 나서 나머지는 전부 required다.
    private static final Map<String, Object> RESPONSE_SCHEMA = Map.ofEntries(
            Map.entry("type", "OBJECT"),
            Map.entry("properties", Map.ofEntries(
                    Map.entry("probabilityReading", probabilityReadingSchema()),
                    Map.entry("chapters", Map.of("type", "ARRAY", "items", chapterSchema())),
                    Map.entry("currentBarrier", barrierSchema()),
                    Map.entry("secondaryBarrier", barrierSchema()),
                    Map.entry("maintenanceInsight", maintenanceSchema()),
                    Map.entry("reselect", reselectSchema()),
                    Map.entry("final", finalSchema()),
                    Map.entry("internal", internalSchema()))),
            Map.entry("required", List.of("probabilityReading", "chapters", "currentBarrier",
                    "reselect", "final", "internal")),
            Map.entry("propertyOrdering", List.of("probabilityReading", "chapters",
                    "currentBarrier", "secondaryBarrier", "maintenanceInsight", "reselect",
                    "final", "internal")));

    // 개수 상한 — 지시(장 3~5, 칩 2~4, 분기점 1~3)의 안전핀.
    private static final int CHAPTER_MAX = 5;
    private static final int CHIP_MAX = 4;
    private static final int TURNING_MAX = 3;
    private static final int LIST_TEXT_MAX = 300;

    private ReadingDraft parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(LlmJson.salvage(json));

            JsonNode probNode = root.path("probabilityReading");
            ReadingDraft.ProbabilityReading probabilityReading = new ReadingDraft.ProbabilityReading(
                    requireText(probNode, "reading"), strings(probNode.path("evidenceIds"), 10));

            List<ReadingDraft.Chapter> chapters = new ArrayList<>();
            for (JsonNode node : root.path("chapters")) {
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
                chapters.add(new ReadingDraft.Chapter(
                        clip(node.path("eyebrow").asText("").trim(), LIST_TEXT_MAX),
                        clip(title, LIST_TEXT_MAX),
                        ReadingVocab.CHAPTER_ROLES.contains(role) ? role : "CORE_CONTRADICTION",
                        clip(answer, 500), reading,
                        psychology(node.path("psychology")),
                        principle.isBlank() ? null : clip(principle, 500),
                        strings(node.path("evidenceIds"), 10)));
            }
            // 장이 하나도 없으면 스토리 리포트가 아니다 — 판독 실패로 처리한다(판정은 유지).
            if (chapters.isEmpty()) {
                log.warn("정밀 판독에 챕터가 없음 — 판독 실패 처리");
                throw new LlmException();
            }

            JsonNode reselectNode = root.path("reselect");
            ReadingDraft.Reselect reselect = new ReadingDraft.Reselect(
                    clip(requireText(reselectNode, "title"), LIST_TEXT_MAX),
                    clip(requireText(reselectNode, "answer"), 500),
                    requireText(reselectNode, "reading"),
                    strings(reselectNode.path("turningPoints"), TURNING_MAX));

            JsonNode finalNode = root.path("final");
            ReadingDraft.Fin fin = new ReadingDraft.Fin(
                    clip(requireText(finalNode, "stateLabel"), LIST_TEXT_MAX),
                    strings(finalNode.path("chipSeeds"), CHIP_MAX));

            JsonNode internal = root.path("internal");
            ReadingDraft.Internal states = new ReadingDraft.Internal(
                    state(internal, "nowState", ReadingVocab.NOW_STATES, "MIXED"),
                    state(internal, "resolveState", ReadingVocab.RESOLVE_STATES, "UNSTABLE"),
                    state(internal, "remainState", ReadingVocab.REMAIN_STATES, "LITTLE_EVIDENCE"),
                    state(internal, "reselectState", ReadingVocab.RESELECT_STATES, "CONDITIONAL"));

            return new ReadingDraft(probabilityReading, chapters,
                    barrier(root.path("currentBarrier")),
                    barrier(root.path("secondaryBarrier")),
                    maintenance(root.path("maintenanceInsight")),
                    reselect, fin, states);
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

    private ReadingDraft.Barrier barrier(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        String answer = node.path("answer").asText("").trim();
        String reading = node.path("reading").asText("").trim();
        if (answer.isBlank()) {
            return null;
        }
        return new ReadingDraft.Barrier(clip(answer, 500), reading,
                strings(node.path("evidenceIds"), 10));
    }

    private ReadingDraft.Maintenance maintenance(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        String title = node.path("title").asText("").trim();
        String answer = node.path("answer").asText("").trim();
        String principle = node.path("repairPrinciple").asText("").trim();
        if (title.isBlank() || answer.isBlank() || principle.isBlank()) {
            return null;
        }
        return new ReadingDraft.Maintenance(clip(title, LIST_TEXT_MAX), clip(answer, 500),
                psychology(node.path("psychology")),
                node.path("reading").asText("").trim(), clip(principle, 500));
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

    // 확률 판독과 마무리가 비면 리포트로 성립하지 않는다 — 판독만 실패시킨다(판정은 유지).
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
