package com.threeam.assessment.service;

import com.threeam.assessment.entity.Deduction;
import java.util.List;
import org.springframework.stereotype.Component;

// 최종 확률을 LLM이 아니라 백엔드가 합산한다.
// LLM은 감점/가점 항목만 판단하고, 여기서 BASE에서 더해 클램프한다 → LLM이 "90%!" 하고 아부할 통로 자체가 없음.
// BASE 50(중립)에서 감점으로 내려가고, 가점으로 올라간다.
// (변천: BASE 25는 항상 바닥 → 50 중립으로. 가점 상한 20/MAX 80은 "나쁜 신호가 잔뜩 쌓인 뒤
//  좋은 신호가 와도 못 뒤집는다"는 문제로 폐지 — 스테일 마음-축 감점은 루브릭이 애초에 안 내보내고
//  (축별 시간 규칙), 남은 감점은 지속 장벽(원인/구조)이라 가점이 그걸 정직하게 상쇄하게 둔다.)
@Component
public class ReunionScorer {

    private static final int BASE = 50;
    private static final int MIN = 3;
    private static final int MAX = 95; // 96~100은 상대의 유효한 재회 제안(확정 100) 몫으로 비워둔다.

    // 감점(음수)과 가점(양수)을 상한 없이 그대로 합산한다. 가점 상한을 없앤 이유:
    // 뒤집힘을 인위적으로 막던 통이었기 때문. 대신 남은 감점이 '지속 장벽'(바람, 새 애인 등)이면
    // 가점이 와도 그 장벽에 눌려 제한적으로만 오르고, 마음-축 감점뿐이면(루브릭이 스테일 감점을
    // 안 내보내므로) 가점이 크게 되살린다. 폭주는 항목별 상한(파싱 MAX_POINTS)과 최종 클램프가 막는다.
    public int apply(List<Deduction> items) {
        int score = BASE;
        for (Deduction item : items) {
            score += item.getDelta();
        }
        return Math.max(MIN, Math.min(MAX, score));
    }
}
