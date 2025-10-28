package com.threeam.assessment.service;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

// 폭력·자해 위기 신호를 백엔드가 직접 훑는다.
// LLM 판단만 믿지 않는 이중화: 사람이 다칠 수 있는 종류라 키워드 스캔으로 한 번 더 거른다.
// 하나라도 걸리면 재회 확률 계산을 건너뛰고 DANGER로 강제한다(거짓 안심 방지).
@Component
public class SafetyScanner {

    private static final List<String> DANGER_KEYWORDS = List.of(
            "자살", "죽고 싶", "죽고싶", "죽어버", "사라지고 싶", "목 매", "목매",
            "자해", "손목", "때렸", "때려", "맞았", "폭행", "폭력", "협박", "감금", "스토킹"
    );

    public boolean isDanger(List<String> messages) {
        for (String message : messages) {
            if (message == null) {
                continue;
            }
            String normalized = message.toLowerCase(Locale.ROOT);
            for (String keyword : DANGER_KEYWORDS) {
                if (normalized.contains(keyword)) {
                    return true;
                }
            }
        }
        return false;
    }
}
