package com.threeam.global.text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 채팅이 스스로 말한 재회 가능성을 숫자로 받아내는 꼬리표. 유저에게는 보이지 않는다 —
// 저장 직전에 본문에서 떼어내고 값만 남긴다.
// 목적은 하나다: 채팅이 말한 방향과 진단이 계산한 확률을 나란히 놓고, 어긋나는 판을
// 실측으로 찾는 것. 어느 쪽을 고칠지는 그 기록을 보고 정한다.
// 답변 본문을 정규식으로 훑어 "높은 편"류를 추정하지 않는다 — 표현이 무한하고, 추정이
// 틀리면 기록 자체가 오염돼 비교의 근거가 사라진다. 모델이 숫자로 말하게 하는 편이 정확하다.
public final class ProbabilityTag {

    // 예: [[가능성:65]]. 공백과 전각 콜론까지 받아준다 — 형식이 조금 어긋났다고 기록을 잃지 않게.
    private static final Pattern TAG =
            Pattern.compile("\\[\\[\\s*가능성\\s*[:：]\\s*(\\d{1,3})\\s*]]");

    private ProbabilityTag() {
    }

    public record Result(String text, Integer probability) {
    }

    public static Result strip(String reply) {
        if (reply == null) {
            return new Result(null, null);
        }
        Matcher matcher = TAG.matcher(reply);
        Integer value = null;
        while (matcher.find()) {
            int parsed = Integer.parseInt(matcher.group(1));
            // 여러 개가 붙어 오면 마지막 것을 쓴다 — 모델이 고쳐 적은 경우 뒤엣것이 결론이다.
            if (parsed >= 0 && parsed <= 100) {
                value = parsed;
            }
        }
        return new Result(TAG.matcher(reply).replaceAll("").strip(), value);
    }
}
