package com.threeam.story.service;

import java.util.List;

// 유저가 "내가 잘못한 건가"를 직접 묻고 있는가. 자책 규칙(persona의 self-blame)은 매 턴 싣지
// 않는다 — 묻지도 않았는데 책임을 가르기 시작하면, 상대 마음을 물은 턴에 유저 자기검열을
// 얹는 답이 된다. 물어온 턴에만 실어 내 책임, 상대 책임, 상황을 나눠 답하게 한다.
//
// LLM 분류기를 따로 두지 않는다(DiagnosisMention과 같은 판단) — 호출이 한 번 더 늘고,
// 이건 AI에 맡길 수준의 판정이 아니다.
final class SelfBlameMention {

    private SelfBlameMention() {
    }

    // 자책, 책임 귀속을 묻는 말들. "잘못" 단독은 뺐다 — "걔가 잘못했잖아"처럼 상대를 향한
    // 문장에도 걸려서, 자책하지 않은 턴에 책임 분리 규칙이 실린다.
    private static final List<String> DIRECT = List.of(
            "내 잘못", "제 잘못", "나 때문", "저 때문", "내 탓", "제 탓",
            "내가 잘못", "제가 잘못", "내가 망친", "제가 망친", "내가 심했", "제가 심했",
            "내가 못해", "제가 못해", "내가 부족", "제가 부족",
            "말 안 했으면", "말 안했으면", "안 그랬으면", "안그랬으면",
            "후회", "자책");

    static boolean referenced(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String text = message.replace(" ", "");
        for (String keyword : DIRECT) {
            if (text.contains(keyword.replace(" ", ""))) {
                return true;
            }
        }
        return false;
    }
}
