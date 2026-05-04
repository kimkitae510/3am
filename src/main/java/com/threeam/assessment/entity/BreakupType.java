package com.threeam.assessment.entity;

import java.util.Arrays;
import java.util.List;

// 이별 유형(1층). 유형이 기본 확률 대역을 정하고, 요인(2층)이 대역 안에서 조정한다.
// LLM은 한국어 라벨로 판정하고(스키마 enum), 저장은 영문 상수 — 라벨을 바꿔도 데이터가 안 깨진다.
// 대역 수치는 TypeBandScorer에 있다(루브릭 문서와 짝).
public enum BreakupType {
    IMPULSIVE("충동형"),        // 싸움의 열기 속 통보, 감정은 남아 있음
    SITUATIONAL("상황형"),      // 일시적 환경(장거리, 군대, 유학, 시험)이 갈라놓음
    EXTERNAL("외부요인형"),     // 고착된 조건(부모 반대, 결혼 시기, 경제)
    FADED("권태식음형"),        // 상대의 설렘과 애정이 잦아듦
    BURNOUT("소진형"),          // 유저의 반복된 행동에 상대가 지쳐 떠남
    RESOLVED("결심완료형"),     // 수주~수개월 고민 끝의 통보, 이미 정리 끝
    TRANSFER("환승형"),         // 상대에게 이미 다른 사람이 있(었)음
    TRUST_BROKEN("신뢰붕괴형"); // 유저 귀책으로 상대의 신뢰가 무너짐

    private final String label;

    BreakupType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static List<String> labels() {
        return Arrays.stream(values()).map(BreakupType::label).toList();
    }

    public static BreakupType fromLabel(String label) {
        if (label == null) {
            return null;
        }
        for (BreakupType type : values()) {
            if (type.label.equals(label.trim())) {
                return type;
            }
        }
        return null;
    }
}
