package com.threeam.assessment.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;

// 정밀 판독(2호출) 결과. 판정(assessments)과 1:1 — 판정이 확정된 뒤 그 위에 서술을 단다.
// 네 질문(상대의 지금 / 결심 강도 / 남은 마음 / 재선택)의 답과 증거, 사건 재구성, 국면이 본체.
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

    // 총평 — 네 질문의 요약 반복이 아니라 사건 전체를 관통하는 판결 서두. 사연 인용 포함.
    @Column(nullable = false, columnDefinition = "TEXT")
    private String overall;

    // 사건 재구성(관계가 뒤집힌 순간) — 사연에 있는 사건만 시간순으로.
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

    @Column(name = "reselect_state", nullable = false, length = 40)
    private String reselectState;

    @Column(name = "reselect_answer", nullable = false, length = 300)
    private String reselectAnswer;

    // 재선택은 조건부 시나리오까지만 — 닫힌 것 / 열린 것 / 다시 움직일 조건. 행동 지시는 채팅 몫.
    @Column(name = "reselect_closed", nullable = false, columnDefinition = "TEXT")
    private String reselectClosed;

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

    @ElementCollection
    @CollectionTable(name = "assessment_reading_evidence",
            joinColumns = @JoinColumn(name = "assessment_id"))
    @OrderColumn(name = "ord")
    @BatchSize(size = 100)
    private List<ReadingEvidence> evidence = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private AssessmentReading(Long assessmentId, Long baseAssessmentId, String overall,
                              String narrative, String nowState, String nowAnswer,
                              String nowReading, String resolveState, String resolveAnswer,
                              String resolveReading, String remainState, String remainAnswer,
                              String remainReading, String reselectState, String reselectAnswer,
                              String reselectClosed, String reselectOpen, String reselectRoute,
                              String phase, Map<String, String> chapterTitles,
                              List<ReadingEvidence> evidence) {
        this.assessmentId = assessmentId;
        this.baseAssessmentId = baseAssessmentId;
        this.overall = overall;
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
        this.reselectState = reselectState;
        this.reselectAnswer = reselectAnswer;
        this.reselectClosed = reselectClosed;
        this.reselectOpen = reselectOpen;
        this.reselectRoute = reselectRoute;
        this.phase = phase;
        this.chapterTitles = chapterTitles;
        this.evidence = evidence != null ? new ArrayList<>(evidence) : new ArrayList<>();
    }
}
