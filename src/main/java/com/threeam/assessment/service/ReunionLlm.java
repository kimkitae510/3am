package com.threeam.assessment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.assessment.AssessmentProperties;
import com.threeam.assessment.dto.ReunionDiagnosis;
import com.threeam.assessment.dto.ReunionDiagnosis.AttachmentSignalItem;
import com.threeam.assessment.dto.ReunionDiagnosis.DeductionItem;
import com.threeam.assessment.dto.ReunionDiagnosis.GuidanceEntry;
import com.threeam.assessment.entity.AttachmentConfidence;
import com.threeam.assessment.entity.AttachmentStyle;
import com.threeam.assessment.entity.GuidanceKind;
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
// 진단 루브릭(회의론자 톤 + 감점/가점 앵커 + 오판 교정 규칙 + JSON 스키마) 전문은 이 서비스의 핵심이라
// 소스에 두지 않고 AssessmentProperties(로컬 rubric.yml, gitignore)로 주입받는다.
@Slf4j
@Component
@RequiredArgsConstructor
public class ReunionLlm {

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final AssessmentProperties assessmentProperties;

    public CompletableFuture<ReunionDiagnosis> diagnose(String memorySummary, List<String> knownFactLines,
                                                        List<ChatMessage> conversation) {
        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(ChatMessage.system(assessmentProperties.getRubric()));
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
            AttachmentStyle partnerAttachment =
                    enumValue(AttachmentStyle.class, root.path("partnerAttachment").asText(null), null);
            // 확신도 누락/오타는 TENTATIVE로 — 행동 관찰만의 판정이라 과신 쪽보다 보수가 안전하다.
            AttachmentConfidence attachmentConfidence = partnerAttachment == null ? null
                    : enumValue(AttachmentConfidence.class,
                            root.path("attachmentConfidence").asText(null), AttachmentConfidence.TENTATIVE);
            List<AttachmentSignalItem> attachmentSignals = attachmentSignals(root, partnerAttachment);
            boolean activeReunionOffer = root.path("activeReunionOffer").asBoolean(false);

            int[] dropped = {0};
            List<DeductionItem> deductions = parseItems(root, "deductions", dropped);
            List<DeductionItem> boosts = parseItems(root, "boosts", dropped);

            // LLM이 감점/가점을 냈는데 축을 못 붙여 전부 폐기되면, 남은 근거가 0이라 점수가 BASE(50)로 나온다.
            // 그 50은 "재회 가능성 50%"가 아니라 "근거가 유실됨"이다 — 근거 없는 확률을 유저에게 보이지 않도록
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

            List<GuidanceEntry> guidance = new ArrayList<>();
            appendGuidance(guidance, root, "do", GuidanceKind.DO);
            appendGuidance(guidance, root, "dont", GuidanceKind.DONT);

            return new ReunionDiagnosis(verdict, partnerAttachment,
                    attachmentConfidence, attachmentSignals, activeReunionOffer,
                    deductions, boosts, guidance,
                    root.path("reason").asText(""), root.path("summary").asText(""), newFacts);
        } catch (Exception e) {
            // 응답 본문(json)에는 사연 기반 진단 내용이 들어 있어 개인정보다 — 로그에 원문을 남기지 않고
            // 길이와 잘림 여부만 남긴다(닫는 중괄호로 안 끝나면 중간에 잘린 응답 — finishReason 로그와 짝).
            boolean truncated = json != null && !json.trim().endsWith("}");
            log.error("재회 진단 JSON 파싱 실패 (본문 길이 {}자, 잘림 의심={})",
                    json == null ? 0 : json.length(), truncated, e);
            throw new LlmException();
        }
    }

    // 세 판단 축. 항목마다 axis를 강제해서 "나쁜 행동 = 감점" 같은 도덕 채점을 걸러낸다.
    private static final Set<String> AXES = Set.of("마음", "복구가능성", "구조");

    // 한 항목이 움직일 수 있는 점수 상한. 앵커 최대치(30)보다 넉넉히 두되, LLM이 실수로 뱉는
    // 폭주값(예: 9999)이 그대로 저장되지 않게 막는다. 부호는 Deduction에서 감점/가점으로 통일된다.
    private static final int MAX_POINTS = 100;

    // 판독 이유 컬럼 길이(VARCHAR(300)) — 넘치면 잘라서 저장 실패를 막는다.
    private static final int RATIONALE_MAX = 300;

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
            String rationale = node.path("rationale").asText("").trim();
            items.add(new DeductionItem(signal, points, node.path("evidence").asText(""),
                    rationale.isBlank() ? null
                            : rationale.length() > RATIONALE_MAX ? rationale.substring(0, RATIONALE_MAX) : rationale));
        }
        return items;
    }

    // 방향별 가이드 개수 상한(폭주 방어). 루브릭은 1~3개를 지시한다.
    private static final int MAX_GUIDANCE_PER_KIND = 3;

    // 근거 컬럼 길이(VARCHAR(200)) — 넘치면 잘라서 저장 실패를 막는다.
    private static final int GUIDANCE_BASIS_MAX = 200;

    private void appendGuidance(List<GuidanceEntry> guidance, JsonNode root, String field, GuidanceKind kind) {
        int added = 0;
        for (JsonNode node : root.path("guidance").path(field)) {
            String advice = node.path("text").asText("").trim();
            if (advice.isBlank() || added >= MAX_GUIDANCE_PER_KIND) {
                continue;
            }
            String basis = node.path("basis").asText("").trim();
            guidance.add(new GuidanceEntry(kind, advice, basis.isBlank() ? null
                    : basis.length() > GUIDANCE_BASIS_MAX ? basis.substring(0, GUIDANCE_BASIS_MAX) : basis));
            added++;
        }
    }

    // 유형 근거 개수 상한(폭주 방어). 루브릭은 2~4개를 지시한다.
    private static final int MAX_ATTACHMENT_SIGNALS = 5;

    // 신호명 저장 컬럼 길이(VARCHAR(100)) — 넘치면 잘라서 저장 실패를 막는다.
    private static final int SIGNAL_NAME_MAX = 100;

    // 근거는 유형이 판정된 경우에만 의미가 있다 — 유형 없는 근거는 버린다(스키마 일관성).
    private List<AttachmentSignalItem> attachmentSignals(JsonNode root, AttachmentStyle attachment) {
        List<AttachmentSignalItem> items = new ArrayList<>();
        if (attachment == null) {
            return items;
        }
        for (JsonNode node : root.path("attachmentSignals")) {
            String signal = node.path("signal").asText("").trim();
            String evidence = node.path("evidence").asText("").trim();
            if (signal.isBlank() || evidence.isBlank() || items.size() >= MAX_ATTACHMENT_SIGNALS) {
                continue;
            }
            items.add(new AttachmentSignalItem(
                    signal.length() > SIGNAL_NAME_MAX ? signal.substring(0, SIGNAL_NAME_MAX) : signal,
                    evidence));
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
