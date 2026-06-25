package com.threeam.assessment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

// 정밀 판독(2호출) 결과. 판정(assessments)과 1:1 — 판정이 확정된 뒤 그 위에 서술을 단다.
// 구성은 표지(overall + 올린/막는 이유 각 1줄)와 여섯 장: 상대의 지금(now) / 결심 강도(resolve) /
// 남은 마음(remain) / 왜 멀어졌는가(narrative=drift 장) / 지금 막는 것(blocking) /
// 다시 움직일 조건(reselect open+route), 그리고 국면(phase).
// 요인 어휘는 여기 없다 — 채점(1호출) 내부 용어라 유저 지면에 꺼내지 않는다.
// state는 내부 값(화면 비노출) — 판독 일관성 앵커이자 골든셋 회귀용.
@Entity
@Table(name = "assessment_reading")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssessmentReading {

    // 판정과 1:1이라 판정 id를 그대로 PK로 쓴다(별도 시퀀스 없음).
    @Id
    @Column(name = "assessment_id")
    private Long assessmentId;

    // 이 판독이 교정한 직전 판정(확률 있는 것). 변동내역(델타)은 저장하지 않고
    // 조회 때 두 판정의 요인 레벨 diff로 계산한다 — 같은 재료면 같은 델타라 저장할 이유가 없다.
    @Column(name = "base_assessment_id")
    private Long baseAssessmentId;

    // 표지 판정 — 상대의 현재 상태를 판결하는 서두. 확률보다 크게 보이는 주인공 문장.
    @Column(nullable = false, columnDefinition = "TEXT")
    private String overall;

    // 표지의 두 줄 — 가능성을 열어두는 가장 큰 이유 / 지금 막는 가장 큰 이유.
    @Column(name = "cover_raise", nullable = false, length = 300)
    private String coverRaise;

    @Column(name = "cover_block", nullable = false, length = 300)
    private String coverBlock;

    // 왜 멀어졌는가 — 결정적 장면들의 해석과 이번 갈등의 상호작용 방식. 사연 재요약이 아니다.
    @Column(nullable = false, columnDefinition = "TEXT")
    private String narrative;

    @Column(name = "now_state", nullable = false, length = 40)
    private String nowState;

    @Column(name = "now_answer", nullable = false, length = 300)
    private String nowAnswer;

    @Column(name = "now_reading", nullable = false, columnDefinition = "TEXT")
    private String nowReading;

    @Column(name = "resolve_state", nullable = false, length = 40)
    private String resolveState;

    @Column(name = "resolve_answer", nullable = false, length = 300)
    private String resolveAnswer;

    @Column(name = "resolve_reading", nullable = false, columnDefinition = "TEXT")
    private String resolveReading;

    @Column(name = "remain_state", nullable = false, length = 40)
    private String remainState;

    @Column(name = "remain_answer", nullable = false, length = 300)
    private String remainAnswer;

    @Column(name = "remain_reading", nullable = false, columnDefinition = "TEXT")
    private String remainReading;

    // 지금 재회를 막는 것 — 감정 문제와 현실 문제를 구분해 짚는 장.
    @Column(nullable = false, columnDefinition = "TEXT")
    private String blocking;

    @Column(name = "reselect_state", nullable = false, length = 40)
    private String reselectState;

    @Column(name = "reselect_answer", nullable = false, length = 300)
    private String reselectAnswer;

    // 다시 움직일 조건 — 아직 열려 있는 근거(open)와 조건부 시나리오(route)까지만.
    // 날짜와 문구를 정해주는 행동 지시는 채팅 몫.
    @Column(name = "reselect_open", nullable = false, columnDefinition = "TEXT")
    private String reselectOpen;

    @Column(name = "reselect_route", nullable = false, columnDefinition = "TEXT")
    private String reselectRoute;

    // 국면 판정 한두 문장. 행동 처방은 여기 없다.
    @Column(nullable = false, length = 300)
    private String phase;

    // 케이스별 장 제목(책 모드의 훅). 키는 ReadingVocab.CHAPTER_KEYS.
    @Convert(converter = ChapterTitlesConverter.class)
    @Column(name = "chapter_titles", nullable = false, columnDefinition = "TEXT")
    private Map<String, String> chapterTitles;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private AssessmentReading(Long assessmentId, Long baseAssessmentId, String overall,
                              String coverRaise, String coverBlock, String narrative,
                              String nowState, String nowAnswer, String nowReading,
                              String resolveState, String resolveAnswer, String resolveReading,
                              String remainState, String remainAnswer, String remainReading,
                              String blocking, String reselectState, String reselectAnswer,
                              String reselectOpen, String reselectRoute,
                              String phase, Map<String, String> chapterTitles) {
        this.assessmentId = assessmentId;
        this.baseAssessmentId = baseAssessmentId;
        this.overall = overall;
        this.coverRaise = coverRaise;
        this.coverBlock = coverBlock;
        this.narrative = narrative;
        this.nowState = nowState;
        this.nowAnswer = nowAnswer;
        this.nowReading = nowReading;
        this.resolveState = resolveState;
        this.resolveAnswer = resolveAnswer;
        this.resolveReading = resolveReading;
        this.remainState = remainState;
        this.remainAnswer = remainAnswer;
        this.remainReading = remainReading;
        this.blocking = blocking;
        this.reselectState = reselectState;
        this.reselectAnswer = reselectAnswer;
        this.reselectOpen = reselectOpen;
        this.reselectRoute = reselectRoute;
        this.phase = phase;
        this.chapterTitles = chapterTitles;
    }
}
