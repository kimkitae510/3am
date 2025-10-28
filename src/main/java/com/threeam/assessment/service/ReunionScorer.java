package com.threeam.assessment.service;

import com.threeam.assessment.entity.Deduction;
import java.util.List;
import org.springframework.stereotype.Component;

// 최종 확률을 LLM이 아니라 백엔드가 합산한다.
// LLM은 감점 항목만 판단하고, 여기서 BASE에서 깎아 클램프한다 → LLM이 "90%!" 하고 아부할 통로 자체가 없음.
// BASE 25: 재회는 0이 아니라 낮은 데서 출발한다는 전제. CAP 70: 아무리 좋아도 상한 → 헛된 확신 차단.
@Component
public class ReunionScorer {

    private static final int BASE = 25;
    private static final int MIN = 5;
    private static final int MAX = 70;

    // 항목별 상한은 두지 않는다(의도적). 큰 감점이 나와도 최악은 MIN(5)에서 바닥나 흡수된다.
    public int apply(List<Deduction> deductions) {
        int score = BASE;
        for (Deduction deduction : deductions) {
            score += deduction.getDelta();   // delta는 음수로 저장돼 있다
        }
        return Math.max(MIN, Math.min(MAX, score));
    }
}
