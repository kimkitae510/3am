package com.threeam.assessment.service;

import com.threeam.assessment.entity.Deduction;
import java.util.List;
import org.springframework.stereotype.Component;

// 최종 확률을 LLM이 아니라 백엔드가 합산한다.
// LLM은 감점/가점 항목만 판단하고, 여기서 BASE에서 더해 클램프한다 → LLM이 "90%!" 하고 아부할 통로 자체가 없음.
// BASE 70에서 감점으로 내려가고, 강한 긍정 신호(가점)가 있을 때만 CAP 80까지 올라간다.
// (이전 BASE 25는 감점 앵커(10~30)보다 작아서 신호 하나면 바닥 5%에 닿았다 — 진단이 사실상 이분법이었다.)
@Component
public class ReunionScorer {

    private static final int BASE = 70;
    private static final int MIN = 5;
    private static final int MAX = 80;

    // 가점 합산 상한. 강한 신호(상대가 먼저 재회 의사)는 점수를 되살리되,
    // 가점이 감점을 통째로 상쇄해 헛된 확신을 만들지 않게 총량을 자른다.
    private static final int BOOST_CAP = 20;

    // 감점(음수 delta)은 상한 없이 전부 반영, 가점(양수 delta)은 합산 후 BOOST_CAP에서 자른다.
    // 큰 감점이 나와도 최악은 MIN(5)에서 바닥나 흡수된다.
    public int apply(List<Deduction> items) {
        int minus = 0;
        int plus = 0;
        for (Deduction item : items) {
            if (item.getDelta() < 0) {
                minus += item.getDelta();
            } else {
                plus += item.getDelta();
            }
        }
        int score = BASE + minus + Math.min(plus, BOOST_CAP);
        return Math.max(MIN, Math.min(MAX, score));
    }
}
