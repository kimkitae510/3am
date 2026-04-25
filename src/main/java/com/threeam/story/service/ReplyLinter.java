package com.threeam.story.service;

import io.micrometer.core.instrument.Metrics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 답변에서 '글자로 판정되는' 규칙 위반만 세어 로그와 지표로 남긴다. 답변을 고치지는 않는다.
//
// 왜 필요했나: 프롬프트를 고칠 때마다 좋아졌는지를 사람이 대화를 읽어 판단하고 있었다.
// 그래서 한 번 안 나온 걸 고쳐진 것으로 착각하고, 다음 사연에서 되살아나는 걸 반복했다
// (실측: 판세 어휘가 한 라운드 사라졌다가 다음 라운드에 재발). 위반율이 없으면 개선인지
// 우연인지 갈리지 않는다 — 최소한 기계로 셀 수 있는 것만이라도 세어서 기준선을 만든다.
//
// 의미로 판정해야 하는 위반(내면 창작, 위로, 잘잘못 배분)은 여기서 못 잡는다. 그건 사람이 봐야 한다.
// 여기 있는 것은 전부 "이 글자가 있으면 무조건 위반"인 것들뿐이라 오탐이 거의 없어야 한다 —
// 애매하면 규칙을 넣지 마라. 오탐이 섞이는 순간 이 지표를 아무도 안 믿게 된다.
@Slf4j
@Component
public class ReplyLinter {

    // 판세, 승부 어휘. '판'은 판단, 재판, 간판처럼 다른 낱말의 일부일 때가 많아
    // 조사가 붙어 홀로 쓰인 경우만 잡는다(앞 글자가 한글이면 합성어로 보고 건너뛴다).
    private static final Pattern BOARD_WORDS =
            Pattern.compile("(?<![가-힣])판(을|이|은|에|도|까지)(?=\\s|,|$)|주도권|밑밥|포석");

    // 문어체 평서 '~거다' 계열. 페르소나는 '~거야'로 못 박혀 있다.
    private static final Pattern WRITTEN_ENDING = Pattern.compile("거다(\\s|,|$)");

    // 마침표는 말풍선을 끊는 자리라 금지다(물음표, 쉼표는 허용). 줄임표(...)는 제외한다.
    private static final Pattern PERIOD_END = Pattern.compile("(?<!\\.)\\.(?!\\.)\\s*$");

    private static final Pattern BULLET = Pattern.compile("^\\s*[-*]\\s|[·•]");

    // 마크다운 강조, 제목.
    private static final Pattern MARKDOWN = Pattern.compile("\\*\\*|^#{1,6}\\s");

    public void inspect(Long storyId, String reply) {
        if (reply == null || reply.isBlank()) {
            return;
        }
        Map<String, Integer> hits = new LinkedHashMap<>();
        count(hits, "판세어휘", BOARD_WORDS, reply);
        count(hits, "거다어미", WRITTEN_ENDING, reply);
        count(hits, "불릿", BULLET, reply);
        count(hits, "마크다운", MARKDOWN, reply);
        // 마침표는 줄 단위로 본다 — 본문 중간의 소수점, 줄임표를 마침표로 세지 않기 위해.
        int periods = 0;
        for (String line : reply.split("\n")) {
            if (PERIOD_END.matcher(line.strip()).find()) {
                periods++;
            }
        }
        if (periods > 0) {
            hits.put("마침표", periods);
        }

        if (hits.isEmpty()) {
            Metrics.counter("chat.reply.clean").increment();
            return;
        }
        // 답변 원문은 사연이라 개인정보다 — 무엇이 몇 번인지만 남기고 문장은 남기지 않는다.
        log.warn("답변 규칙 위반 storyId={} {}", storyId, hits);
        hits.forEach((rule, n) -> Metrics.counter("chat.reply.violation", "rule", rule).increment(n));
    }

    private void count(Map<String, Integer> hits, String name, Pattern pattern, String reply) {
        int n = 0;
        var matcher = pattern.matcher(reply);
        while (matcher.find()) {
            n++;
        }
        if (n > 0) {
            hits.put(name, n);
        }
    }

    // 테스트와 운영 점검용 — 어떤 규칙이 걸렸는지만 돌려준다.
    public List<String> violatedRules(String reply) {
        Map<String, Integer> hits = new LinkedHashMap<>();
        count(hits, "판세어휘", BOARD_WORDS, reply);
        count(hits, "거다어미", WRITTEN_ENDING, reply);
        count(hits, "불릿", BULLET, reply);
        count(hits, "마크다운", MARKDOWN, reply);
        return new ArrayList<>(hits.keySet());
    }
}
