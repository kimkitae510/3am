package com.threeam.story.service;

import java.util.List;
import java.util.regex.Pattern;

// 자유입력에서 유저가 진단 결과 자체를 물었는가. 평소 상담에는 진단을 안 싣는 정책이라
// 이 판정이 없으면 "진단은 70%였는데 왜 지금 연락하지 말라는 거예요?"에 진단을 못 보고 답한다.
//
// LLM 분류기를 따로 두지 않는다. 호출이 한 번 더 늘고, 이건 AI에 맡길 수준의 판정이 아니다.
// 키워드가 자주 놓친다는 데이터가 쌓이면 그때 칩 추천 호출에 needsDiagnosis 한 칸을 얹으면 된다.
final class DiagnosisMention {

    private DiagnosisMention() {
    }

    // 단독으로 써도 오탐이 거의 없는 말들. "결과"는 뺐다 — "만난 결과가 어땠냐면" 같은
    // 일상 문장에 그대로 걸려서, 진단을 안 물은 턴에 확률이 실린다.
    private static final List<String> DIRECT = List.of(
            "진단", "확률", "퍼센트", "재회 가능성", "가능성이 몇", "가능성 몇",
            "낮게 나왔", "높게 나왔", "낮게 나온", "높게 나온",
            "왜 다르게", "왜 반대", "아까랑 다르");

    // 숫자에 붙은 %만 센다. 맨 % 하나는 다른 맥락에서도 나온다.
    private static final Pattern PERCENT = Pattern.compile("\\d\\s*%");

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
        return PERCENT.matcher(message).find();
    }
}
