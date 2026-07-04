package com.threeam.assessment.dto;

import com.threeam.assessment.dto.ReunionDiagnosis.TimeInsight;
import java.util.List;

// 정밀 판독의 산출물 — v11.1 계약(지시 전문은 로컬 reading.yml, 출력 형식과 1:1).
// 진단 문장(summary, diagnosis, timeInsight)은 판독이 쓰지 않는다: 1호출이 확정한 것을
// 서버가 그대로 병합한다(2호출이 다시 해석하거나 고쳐 쓸 기회를 없앤다).
// 판독이 실제로 쓰는 것은 심층 장(analysisChapters)과 행동 계획(actionPlan), 후속 칩이다.
// 이 객체가 그대로 JSON 직렬화되어 assessment_reading.body에 저장되고 화면으로 내려간다.
public record ReadingDraft(
        String diagnosisSummary,
        TimeInsight timeInsight,          // 시간이 판에 어떻게 작용하는지의 보조 진단. 없으면 null
        List<Diagnosis> diagnosis,
        String analysisSectionTitle,      // 심층 장 묶음의 큰 질문(프론트 섹션 제목)
        List<Chapter> analysisChapters,
        ActionPlan actionPlan,
        List<String> chipSeeds,
        Internal internal) {

    // group은 핵심/조건부/추가, level은 요인 판정과 같은 어휘(매우유리~매우불리),
    // evidenceState는 확인됨/부재 확인됨/부분 — 화면이 "아직 모르는 것"을 구분해 그린다.
    public record Diagnosis(String key, String label, String group, int rank, String level,
                            String evidenceState, String headline, String reading,
                            List<String> factIds) {
    }

    public record Chapter(String eyebrow, String title, String chapterRole, String interpretationId,
                          String answer, String reading, Psychology psychology,
                          String repairPrinciple, List<String> evidenceIds) {
    }

    public record Psychology(String concept, String reading) {
    }

    // 지금 어떻게 움직일지. 시점(timing)과 그 이유, 멈출 조건까지 한 덩이로 준다 —
    // 행동만 있고 멈출 조건이 없으면 유저는 반응이 없을 때 계속 민다.
    public record ActionPlan(String title, String stance, String answer, String timing,
                             String whyThisTiming, String goal, List<String> doList,
                             String stopCondition, List<String> avoid) {
    }

    public record Internal(String nowState, String resolveState, String remainState,
                           String reselectState) {
    }
}
