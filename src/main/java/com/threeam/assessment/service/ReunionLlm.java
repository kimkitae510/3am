package com.threeam.assessment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.assessment.AssessmentProperties;
import com.threeam.assessment.dto.RelationshipPsychology;
import com.threeam.assessment.dto.ReunionDiagnosis;
import com.threeam.assessment.dto.ReunionDiagnosis.FactorItem;
import com.threeam.assessment.dto.ReunionDiagnosis.WatchItem;
import com.threeam.assessment.entity.BreakupType;
import com.threeam.assessment.entity.FactorLevel;
import com.threeam.assessment.entity.FactorName;
import com.threeam.assessment.entity.JumpRule;
import com.threeam.assessment.entity.RelapseRisk;
import com.threeam.assessment.entity.ReplacementStage;
import com.threeam.assessment.entity.ReunionVerdict;
import com.threeam.llm.ChatMessage;
import com.threeam.llm.LlmClient;
import com.threeam.llm.LlmException;
import com.threeam.llm.LlmJson;
import com.threeam.match.MatchTaxonomy;
import com.threeam.match.entity.SubReasons;
import com.threeam.story.entity.StoryFact;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 분석 리포트 LLM 호출 담당(v2: 대역+요인 체계). 대화 + 원장을 루브릭으로 감싸
// 유형(1층)과 요인 판정(2층)을 JSON으로 받아 파싱한다.
// 최종 확률은 여기서 만들지 않는다 → 백엔드(TypeBandScorer)가 대역과 상수로 계산한다.
// 분석 루브릭 전문은 이 서비스의 핵심이라 소스에 두지 않고
// AssessmentProperties(로컬 rubric.yml, gitignore)로 주입받는다.
@Slf4j
@Component
@RequiredArgsConstructor
public class ReunionLlm {

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final AssessmentProperties assessmentProperties;

    // todayLine: "오늘 날짜: ..." — 루브릭의 시간 규칙(5주/3개월, 소진형 1개월)의 기준점.
    // previousDigest: 직전 분석 요지(유형, 확률, 요인 판정) — 새 사실 없이 유형이 흔들리는 것을 막는다.
    public CompletableFuture<ReunionDiagnosis> diagnose(List<String> knownFactLines,
                                                        List<ChatMessage> conversation,
                                                        String todayLine, String previousDigest,
                                                        String intakeBlock) {
        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(ChatMessage.system(assessmentProperties.getRubric()));
        if (knownFactLines != null && !knownFactLines.isEmpty()) {
            prompt.add(ChatMessage.system("이미 기록된 사실(괄호는 기록일):\n- "
                    + String.join("\n- ", knownFactLines)));
        }
        // 매칭 분류 지시. 대화 앞(고정분)에 둬서 캐시를 받게 한다 — 사전이 길어 매번 정가로 내면 비싸다.
        // 문구는 서비스 자산이라 코드에 두지 않는다 — rubric.yml(로컬)에서 주입한다.
        String matchGuide = assessmentProperties.getMatchGuide();
        if (matchGuide != null && !matchGuide.isBlank()) {
            prompt.add(ChatMessage.system(matchGuide));
        }
        // 날짜와 직전 분석은 매번 바뀌는 재료라 고정분(루브릭, 사전) 뒤에 둔다 — 캐시 프리픽스 보호.
        if (todayLine != null && !todayLine.isBlank()) {
            prompt.add(ChatMessage.system(todayLine));
        }
        if (previousDigest != null && !previousDigest.isBlank()) {
            prompt.add(ChatMessage.system(previousDigest));
        }
        // 폼으로 받은 기본 정보. 유저가 고른 값이라 대화에서 추론할 필요가 없는 자리다.
        if (intakeBlock != null && !intakeBlock.isBlank()) {
            prompt.add(ChatMessage.system(intakeBlock));
        }
        prompt.addAll(conversation);
        // 루브릭 깊숙한 규칙은 긴 프롬프트에서 자주 무시된다(v1 실측: 관점 뒤집힘, 이중 계상이
        // 규칙 신설 후에도 재발). 제일 잘 어기는 것만 프롬프트 맨 끝에 출력 직전 점검으로 다시 박는다.
        // 점검 문구도 서비스 자산이라 코드에 두지 않는다 — rubric.yml(로컬)에서 주입한다.
        String finalCheck = assessmentProperties.getFinalCheck();
        if (finalCheck != null && !finalCheck.isBlank()) {
            prompt.add(ChatMessage.system(finalCheck));
        }
        // 분석은 긴 루브릭 일관 적용이 필요해 정밀 판단 경로로 — 설정에 따라 더 강한 모델이 배정된다.
        // 파싱 실패의 자동 재시도는 없다 — temperature 0의 즉시 재시도는 같은 실패를 재생산하고
        // 분석 1회분이 소리 없이 2배 과금된다(v1 실측). 재시도는 유저 버튼, 반복 실패는 쿨다운 가드.
        return llmClient.generateJsonDeep(prompt, RESPONSE_SCHEMA).thenApply(this::parse);
    }

    // 요인 판정 항목의 스키마. name과 level을 enum으로 못 박는 게 핵심 — 슬롯 밖 요인이나
    // 3단계 밖 판정은 생성 단계에서 나올 수 없다. stage는 대체자 불리의 세분(정황/정착).
    private static Map<String, Object> factorItemSchema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "name", Map.of("type", "STRING", "enum", FactorName.labels()),
                        "level", Map.of("type", "STRING",
                                "enum", List.of("매우유리", "유리", "중립", "불리", "매우불리")),
                        "evidence", Map.of("type", "STRING"),
                        "rationale", Map.of("type", "STRING"),
                        "stage", Map.of("type", "STRING", "nullable", true,
                                "enum", List.of("정황", "정착"))),
                "required", List.of("name", "level", "evidence", "rationale"),
                "propertyOrdering", List.of("name", "level", "evidence", "rationale", "stage"));
    }

    private static Map<String, Object> watchItemSchema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "point", Map.of("type", "STRING"),
                        "effect", Map.of("type", "STRING")),
                "required", List.of("point", "effect"),
                "propertyOrdering", List.of("point", "effect"));
    }

    private static Map<String, Object> relapseRiskSchema() {
        return Map.of(
                "type", "OBJECT",
                "nullable", true,
                "properties", Map.of(
                        "level", Map.of("type", "STRING",
                                "enum", List.of("낮음", "중간", "높음")),
                        "reason", Map.of("type", "STRING")),
                "required", List.of("level", "reason"),
                "propertyOrdering", List.of("level", "reason"));
    }

    // 관계 심리(확률과 무관한 이해용 층)의 스키마. 라벨을 enum으로 못 박아 사전 밖 어휘를
    // 생성 단계에서 차단한다.
    // nullable로 두지 않고 전부 required로 강제한다 — nullable 객체는 모델이 절차에서
    // 빠지는 순간 조용히 생략하는 게 실측됐다(matchProfile이 v2 전환 직후 통째로 비어
    // 나온 것과 같은 자리). 판단이 안 서는 판의 탈출구는 '필드 생략'이 아니라 라벨
    // 자체다(판단보류, 뚜렷하지않음) — 그래야 "못 읽었다"와 "안 냈다"가 구분된다.
    private static Map<String, Object> attachmentStyleSchema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "label", Map.of("type", "STRING",
                                "enum", RelationshipPsychology.ATTACHMENT_LABELS),
                        "confidence", Map.of("type", "STRING",
                                "enum", RelationshipPsychology.CONFIDENCE_LABELS)),
                "required", List.of("label", "confidence"),
                "propertyOrdering", List.of("label", "confidence"));
    }

    private static Map<String, Object> relationshipPsychologySchema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "attachment", Map.of(
                                "type", "OBJECT",
                                "properties", Map.of(
                                        "user", attachmentStyleSchema(),
                                        "partner", attachmentStyleSchema(),
                                        "description", Map.of("type", "STRING")),
                                "required", List.of("user", "partner", "description"),
                                "propertyOrdering", List.of("user", "partner", "description")),
                        "interactionPattern", Map.of(
                                "type", "OBJECT",
                                "properties", Map.of(
                                        "label", Map.of("type", "STRING",
                                                "enum", RelationshipPsychology.PATTERN_LABELS),
                                        "confidence", Map.of("type", "STRING",
                                                "enum", RelationshipPsychology.CONFIDENCE_LABELS),
                                        "description", Map.of("type", "STRING")),
                                "required", List.of("label", "confidence", "description"),
                                "propertyOrdering", List.of("label", "confidence", "description")),
                        "needConflict", Map.of(
                                "type", "OBJECT",
                                "properties", Map.of(
                                        "left", Map.of("type", "STRING", "nullable", true),
                                        "right", Map.of("type", "STRING", "nullable", true),
                                        "description", Map.of("type", "STRING")),
                                "required", List.of("description"),
                                "propertyOrdering", List.of("left", "right", "description"))),
                "required", List.of("attachment", "interactionPattern", "needConflict"),
                "propertyOrdering", List.of("attachment", "interactionPattern", "needConflict"));
    }

    // 매칭 분류의 스키마. 어휘를 enum으로 못 박는 게 핵심 — 자유 서술을 허용하면
    // "여사친 문제"처럼 뜻은 같고 글자가 다른 값이 나와 사례의 태그와 안 겹친다.
    private static Map<String, Object> matchProfileSchema() {
        // nullable을 두지 않는다 — v2 전환 후 모델이 이 필드만 조용히 null로 내는 게 실측됐다
        // (프롬프트 지시 보강으로도 재발). 객체 생성을 스키마로 강제하고, 내부 필드가 전부
        // 비면 파서가 null로 접는다(잠금 판정, 정보 없음 케이스는 그 경로로 처리된다).
        return Map.ofEntries(
                Map.entry("type", "OBJECT"),
                Map.entry("properties", Map.ofEntries(
                        Map.entry("reason", Map.of("type", "STRING", "nullable", true,
                                "enum", MatchTaxonomy.REASONS)),
                        Map.entry("subReasons", Map.of("type", "ARRAY",
                                "items", Map.of("type", "STRING",
                                        "enum", List.copyOf(MatchTaxonomy.SUB_REASONS)))),
                        Map.entry("dumper", Map.of("type", "STRING", "nullable", true,
                                "enum", MatchTaxonomy.DUMPERS)),
                        Map.entry("fault", Map.of("type", "STRING", "nullable", true,
                                "enum", MatchTaxonomy.FAULTS)),
                        Map.entry("contactState", Map.of("type", "STRING", "nullable", true,
                                "enum", MatchTaxonomy.CONTACT_STATES)),
                        Map.entry("monthsSinceBreakup", Map.of("type", "INTEGER", "nullable", true)),
                        Map.entry("datingMonths", Map.of("type", "INTEGER", "nullable", true)),
                        Map.entry("ageGroup", Map.of("type", "STRING", "nullable", true)),
                        Map.entry("gender", Map.of("type", "STRING", "nullable", true)),
                        Map.entry("repeatBreakup", Map.of("type", "BOOLEAN", "nullable", true)),
                        Map.entry("partnerHasNew", Map.of("type", "BOOLEAN", "nullable", true)))),
                Map.entry("propertyOrdering", List.of("reason", "subReasons", "dumper", "fault",
                        "contactState", "monthsSinceBreakup", "datingMonths", "ageGroup",
                        "gender", "repeatBreakup", "partnerHasNew")));
    }

    // 분석 응답의 문법을 생성 단계에서 강제하는 스키마. 프롬프트(rubric.yml)의 JSON 지시와 짝이며,
    // 루브릭을 고쳐 필드가 바뀌면 여기도 같이 고쳐야 한다 — 스키마에 없는 필드는 모델이 낼 수 없다.
    // propertyOrdering은 루브릭의 절차 순서와 맞춘다(판정 → 유형 → 요인 → 전망 → 관찰 → 총평).
    private static final Map<String, Object> RESPONSE_SCHEMA = Map.ofEntries(
            Map.entry("type", "OBJECT"),
            Map.entry("properties", Map.ofEntries(
                    Map.entry("verdict", Map.of("type", "STRING",
                            "enum", List.of("POSSIBLE", "INSUFFICIENT", "DATING", "REUNITED"))),
                    Map.entry("activeReunionOffer", Map.of("type", "BOOLEAN")),
                    Map.entry("breakupType", Map.of("type", "STRING", "nullable", true,
                            "enum", BreakupType.labels())),
                    Map.entry("typeEvidence", Map.of("type", "STRING", "nullable", true)),
                    // 구조화 출력의 enum은 모델이 낼 수 있는 값을 강제한다 — 여기 빠진 점프는
                    // 루브릭이 아무리 시켜도 못 나오고, 모델은 목록에 있는 엉뚱한 값으로 밀려난다
                    // (실측: 장벽해소를 의도한 판이 상대결혼약혼으로 찍혀 8%가 나왔다).
                    // JumpRule을 추가할 때 이 목록을 같이 고치는 걸 잊지 마라.
                    Map.entry("jumpRule", Map.of("type", "STRING",
                            "enum", java.util.Arrays.stream(JumpRule.values())
                                    .map(JumpRule::label).toList())),
                    Map.entry("factors", Map.of("type", "ARRAY", "items", factorItemSchema())),
                    Map.entry("relapseRisk", relapseRiskSchema()),
                    Map.entry("relationshipPsychology", relationshipPsychologySchema()),
                    Map.entry("watchFor", Map.of("type", "ARRAY", "items", watchItemSchema())),
                    Map.entry("unansweredQuestions", Map.of("type", "ARRAY",
                            "items", Map.of("type", "STRING"))),
                    Map.entry("matchProfile", matchProfileSchema()),
                    Map.entry("reason", Map.of("type", "STRING")),
                    Map.entry("newFacts", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))))),
            // 배열류와 유형은 필수에서 뺀다 — 잠금 판정(DATING 등)은 루브릭이 비우라고 지시하는데
            // 필수로 걸면 억지로 채우게 된다.
            Map.entry("required", List.of("verdict", "activeReunionOffer",
                    "jumpRule", "matchProfile", "relationshipPsychology", "reason")),
            Map.entry("propertyOrdering", List.of("verdict", "activeReunionOffer", "breakupType",
                    "typeEvidence", "jumpRule", "factors", "relapseRisk",
                    "relationshipPsychology", "watchFor", "unansweredQuestions",
                    "matchProfile", "reason", "newFacts")));

    private ReunionDiagnosis parse(String json) {
        try {
            // 코드펜스, 잡설이 붙은 응답을 한 번 다듬어 살린다 — 분석 실패는 유저에게 502로 보이는 비용이다.
            JsonNode root = objectMapper.readTree(LlmJson.salvage(json));
            ReunionVerdict verdict = enumValue(ReunionVerdict.class, root.path("verdict").asText(null),
                    ReunionVerdict.POSSIBLE);
            boolean activeReunionOffer = root.path("activeReunionOffer").asBoolean(false);

            BreakupType breakupType = BreakupType.fromLabel(root.path("breakupType").asText(null));
            JumpRule jumpRule = JumpRule.fromLabel(root.path("jumpRule").asText(null));
            List<FactorItem> factors = parseFactors(root);

            // 유형 없는 POSSIBLE은 확률을 계산할 대역이 없다 — 근거 없는 확률을 유저에게 보이지 않게
            // INSUFFICIENT로 강등한다. 예외 둘: 활성 재회 제안(100 확정), 점프 판(유형이 비어도
            // 점프 대역만으로 계산된다 — 상대에게 귀속할 사실 없이 유저 사정으로 끝난 이별이 그 경로다).
            if (verdict == ReunionVerdict.POSSIBLE && !activeReunionOffer && breakupType == null
                    && jumpRule == JumpRule.NONE) {
                log.warn("분석 유형 누락 — 근거 없는 확률 방지 위해 INSUFFICIENT로 강등");
                verdict = ReunionVerdict.INSUFFICIENT;
            }

            RelapseRisk relapseRisk = RelapseRisk.fromLabel(
                    root.path("relapseRisk").path("level").asText(null));
            String relapseReason = clip(root.path("relapseRisk").path("reason").asText(""), TEXT_MAX);

            // 개수 제한은 폭주 방어용 안전핀뿐(정상 분석에선 닿지 않는다). 길이는 원장 컬럼에 맞춰 자른다.
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

            return new ReunionDiagnosis(verdict, activeReunionOffer, breakupType,
                    clip(root.path("typeEvidence").asText(""), TEXT_MAX),
                    jumpRule,
                    factors, relapseRisk, relapseReason, parseWatch(root),
                    parseUnanswered(root),
                    matchProfile(root),
                    relationshipPsychology(root),
                    root.path("reason").asText(""), newFacts);
        } catch (Exception e) {
            // 응답 본문(json)에는 사연 기반 분석 내용이 들어 있어 개인정보다 — 원문 전체는 남기지 않는다.
            boolean truncated = json != null && !json.trim().endsWith("}");
            log.error("분석 리포트 JSON 파싱 실패 (본문 길이 {}자, 잘림 의심={}, 꼬리=[{}])",
                    json == null ? 0 : json.length(), truncated, tail(json), e);
            throw new LlmException();
        }
    }

    // 문자열 컬럼 공통 길이(VARCHAR(300)) — 넘치면 잘라서 저장 실패를 막는다.
    private static final int TEXT_MAX = 300;

    // 관찰 포인트 상한. 루브릭이 1~2개를 지시하지만 스키마는 배열이라 안전핀을 건다.
    private static final int WATCH_MAX = 2;

    // 요인은 항상 전 슬롯으로 정규화한다: 중복은 첫 판정만 남기고, 누락은 중립("근거 없음")으로 채운다.
    // 슬롯이 고정이어야 화면과 재계산(제안 번복)이 요인 유무를 걱정하지 않는다.
    private List<FactorItem> parseFactors(JsonNode root) {
        Map<FactorName, FactorItem> byName = new EnumMap<>(FactorName.class);
        for (JsonNode node : root.path("factors")) {
            FactorName name = FactorName.fromLabel(node.path("name").asText(null));
            FactorLevel level = FactorLevel.fromLabel(node.path("level").asText(null));
            if (name == null || level == null) {
                log.warn("분석 요인 폐기(슬롯 밖): name={} level={}",
                        node.path("name").asText(""), node.path("level").asText(""));
                continue;
            }
            if (byName.containsKey(name)) {
                log.warn("분석 요인 중복 — 첫 판정만 유지: {}", name);
                continue;
            }
            String rationale = clip(node.path("rationale").asText("").trim(), TEXT_MAX);
            byName.put(name, new FactorItem(name, level,
                    node.path("evidence").asText("").trim(),
                    rationale.isBlank() ? null : rationale,
                    ReplacementStage.fromLabel(node.path("stage").asText(null))));
        }
        List<FactorItem> factors = new ArrayList<>();
        for (FactorName name : FactorName.values()) {
            factors.add(byName.getOrDefault(name,
                    new FactorItem(name, FactorLevel.NEUTRAL, NO_EVIDENCE, null, null)));
        }
        return factors;
    }

    // 근거 없는 슬롯의 표준 문구. 화면의 "이걸 알려주면 정확해져요" 안내가 이 값으로 갈린다.
    public static final String NO_EVIDENCE = "근거 없음";

    // 상담자가 물었는데 답이 안 온 질문. 화면이 그대로 보여주므로 개수와 길이를 여기서 막는다.
    private static final int UNANSWERED_MAX = 3;

    private List<String> parseUnanswered(JsonNode root) {
        List<String> out = new ArrayList<>();
        for (JsonNode node : root.path("unansweredQuestions")) {
            String q = node.asText("").trim();
            if (q.isBlank() || out.size() >= UNANSWERED_MAX) {
                continue;
            }
            out.add(clip(q, TEXT_MAX));
        }
        return out;
    }

    private List<WatchItem> parseWatch(JsonNode root) {
        List<WatchItem> items = new ArrayList<>();
        for (JsonNode node : root.path("watchFor")) {
            String point = clip(node.path("point").asText("").trim(), TEXT_MAX);
            String effect = clip(node.path("effect").asText("").trim(), TEXT_MAX);
            if (point.isBlank() || effect.isBlank() || items.size() >= WATCH_MAX) {
                continue;
            }
            items.add(new WatchItem(point, effect));
        }
        return items;
    }

    private String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    // 잘림 원인 판별용 꼬리 길이. 짧게 잡는다 — 분석 문장이 통째로 남으면 로그가 개인정보 저장소가 된다.
    private static final int TAIL_LENGTH = 120;

    private String tail(String json) {
        if (json == null || json.isEmpty()) {
            return "";
        }
        String trimmed = json.stripTrailing();
        return trimmed.length() <= TAIL_LENGTH ? trimmed
                : trimmed.substring(trimmed.length() - TAIL_LENGTH);
    }

    // 관계 심리 파싱. 사전 밖 라벨은 그 축만 버리고, 세 축이 전부 비면 null로 접는다 —
    // 확률과 무관한 층이라 일부가 죽어도 진단 전체를 실패시키지 않는다.
    private RelationshipPsychology relationshipPsychology(JsonNode root) {
        JsonNode node = root.path("relationshipPsychology");
        if (!node.isObject()) {
            return null;
        }
        RelationshipPsychology.Attachment attachment = parseAttachment(node.path("attachment"));
        RelationshipPsychology.PatternItem pattern = parsePattern(node.path("interactionPattern"));
        RelationshipPsychology.NeedConflict needs = parseNeedConflict(node.path("needConflict"));
        if (attachment == null && pattern == null && needs == null) {
            return null;
        }
        return new RelationshipPsychology(attachment, pattern, needs);
    }

    private RelationshipPsychology.Attachment parseAttachment(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        RelationshipPsychology.Style user = parseStyle(node.path("user"));
        RelationshipPsychology.Style partner = parseStyle(node.path("partner"));
        String description = clip(node.path("description").asText("").trim(), TEXT_MAX);
        if (user == null && partner == null) {
            return null;
        }
        return new RelationshipPsychology.Attachment(user, partner,
                description.isBlank() ? null : description);
    }

    private RelationshipPsychology.Style parseStyle(JsonNode node) {
        String label = node.path("label").asText("").trim();
        if (!RelationshipPsychology.ATTACHMENT_LABELS.contains(label)) {
            if (!label.isBlank()) {
                log.warn("애착 라벨 폐기(사전에 없음): {}", label);
            }
            return null;
        }
        String confidence = node.path("confidence").asText("").trim();
        return new RelationshipPsychology.Style(label,
                RelationshipPsychology.CONFIDENCE_LABELS.contains(confidence) ? confidence : "낮음");
    }

    private RelationshipPsychology.PatternItem parsePattern(JsonNode node) {
        String label = node.path("label").asText("").trim();
        if (!RelationshipPsychology.PATTERN_LABELS.contains(label)) {
            if (!label.isBlank()) {
                log.warn("관계 패턴 라벨 폐기(사전에 없음): {}", label);
            }
            return null;
        }
        String confidence = node.path("confidence").asText("").trim();
        String description = clip(node.path("description").asText("").trim(), TEXT_MAX);
        return new RelationshipPsychology.PatternItem(label,
                RelationshipPsychology.CONFIDENCE_LABELS.contains(confidence) ? confidence : "낮음",
                description.isBlank() ? null : description);
    }

    // 욕구는 자유 서술이라 사전 검증이 없다 — 길이만 막는다. 짧은 명사구를 기대하는 칸이라
    // 문장이 오면 화면이 깨지므로 라벨 길이로 자른다.
    private static final int NEED_MAX = 30;

    private RelationshipPsychology.NeedConflict parseNeedConflict(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        String left = clip(node.path("left").asText("").trim(), NEED_MAX);
        String right = clip(node.path("right").asText("").trim(), NEED_MAX);
        String description = clip(node.path("description").asText("").trim(), TEXT_MAX);
        if (left.isBlank() && right.isBlank()) {
            return null;
        }
        return new RelationshipPsychology.NeedConflict(
                left.isBlank() ? null : left,
                right.isBlank() ? null : right,
                description.isBlank() ? null : description);
    }

    // 사전에 없는 값은 사례와 겹칠 수 없으니 저장할 값어치가 없다 — 통째로 버리는 대신 항목별로 거른다.
    // 전 필드가 비면 null을 돌려줘 "뽑지 못함"과 "빈 프로필을 뽑음"을 구분한다.
    private ReunionDiagnosis.MatchProfileItem matchProfile(JsonNode root) {
        JsonNode node = root.path("matchProfile");
        if (!node.isObject()) {
            return null;
        }
        String reason = dictionaryValue(node, "reason", MatchTaxonomy::isReason);

        List<String> subReasons = new ArrayList<>();
        for (JsonNode item : node.path("subReasons")) {
            String tag = item.asText("").trim();
            if (!MatchTaxonomy.isSubReason(tag)) {
                if (!tag.isBlank()) {
                    // 사전 밖 어휘가 계속 나오면 사전이 현실을 못 담고 있다는 신호다(태그 신설 근거).
                    log.warn("매칭 서브태그 폐기(사전에 없음): {}", tag);
                }
                continue;
            }
            if (!subReasons.contains(tag) && subReasons.size() < SubReasons.MAX) {
                subReasons.add(tag);
            }
        }

        String dumper = dictionaryValue(node, "dumper", MatchTaxonomy.DUMPERS::contains);
        String fault = dictionaryValue(node, "fault", MatchTaxonomy.FAULTS::contains);
        String contactState =
                dictionaryValue(node, "contactState", MatchTaxonomy.CONTACT_STATES::contains);
        Integer monthsSinceBreakup = monthValue(node, "monthsSinceBreakup");
        Integer datingMonths = monthValue(node, "datingMonths");
        String ageGroup = text(node, "ageGroup", AGE_GROUP_MAX);
        String gender = text(node, "gender", GENDER_MAX);
        Boolean repeatBreakup = node.path("repeatBreakup").isBoolean()
                ? node.path("repeatBreakup").asBoolean() : null;
        Boolean partnerHasNew = node.path("partnerHasNew").isBoolean()
                ? node.path("partnerHasNew").asBoolean() : null;

        boolean empty = reason == null && subReasons.isEmpty() && dumper == null && fault == null
                && contactState == null && monthsSinceBreakup == null && datingMonths == null
                && ageGroup == null && gender == null && repeatBreakup == null
                && partnerHasNew == null;
        if (empty) {
            // 정상 분석에서 반복되면 스키마/지시가 또 뚫린 것 — 매칭이 조용히 죽는 걸 관측 가능하게.
            log.warn("매칭 분류 미추출 — matchProfile이 비어 있음");
        }
        return empty ? null : new ReunionDiagnosis.MatchProfileItem(reason, subReasons, dumper,
                fault, contactState, monthsSinceBreakup, datingMonths, ageGroup, gender,
                repeatBreakup, partnerHasNew);
    }

    // 프로필 문자열 컬럼 길이 — 넘치면 저장이 실패하므로 입구에서 자른다.
    private static final int AGE_GROUP_MAX = 20;
    private static final int GENDER_MAX = 10;

    // 개월 수 상한(약 42년). 음수와 폭주값은 버킷 계산을 망가뜨린다.
    private static final int MONTHS_MAX = 500;

    private String dictionaryValue(JsonNode node, String field, java.util.function.Predicate<String> allowed) {
        String value = node.path(field).asText("").trim();
        return allowed.test(value) ? value : null;
    }

    private Integer monthValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber()) {
            return null;
        }
        int months = value.asInt();
        return months < 0 || months > MONTHS_MAX ? null : months;
    }

    private String text(JsonNode node, String field, int max) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
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
