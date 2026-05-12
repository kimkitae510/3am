package com.threeam.global.text;

// 채팅 말투에서 문장 끝 마침표를 코드로 걷어낸다 — 프롬프트 지시("마침표 쓰지 마라")는
// 확률적으로 새는 게 실측이라, 숫자를 코드가 계산하듯 문체 규칙도 결정적인 쪽이 맡는다.
// 소수(3.5)와 말줄임(...)은 보존한다.
public final class Periods {

    private Periods() {
    }

    // 앞뒤가 숫자나 마침표가 아닌 단독 마침표만 제거. 제거 후 줄 끝 공백 정리.
    public static String strip(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String stripped = text.replaceAll("(?<![.\\d])\\.(?![.\\d])", "");
        return stripped.replaceAll("[ \\t]+(\\n|$)", "$1");
    }
}
