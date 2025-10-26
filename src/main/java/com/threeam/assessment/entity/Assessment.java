package com.threeam.assessment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

// 한 시점의 재회 진단 결과. 사연(storyId)별로 쌓여 시간에 따른 확률 변화 히스토리가 된다.
@Entity
@Table(name = "assessments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Assessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long storyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReunionVerdict verdict;

    // 졸업 판정(LET_GO)일 땐 null. 숫자를 주면 그 숫자에 매달리므로 일부러 안 준다.
    @Column
    private Integer probability;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BreakupType myBreakupType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PartnerType partnerType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    // 점수의 근거가 된 신호값 스냅샷. LLM/폼이 뽑은 입력을 그대로 남겨 투명성·재계산에 쓴다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Initiator whoEnded;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ContactStatus contactStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BreakupReason breakupReason;

    @Column(nullable = false)
    private boolean partnerNewPerson;

    @Column(nullable = false)
    private int relationshipMonths;

    @Column(nullable = false)
    private boolean pastReunionFailed;

    @Column(nullable = false)
    private int daysSinceBreakup;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private Assessment(Long storyId, ReunionVerdict verdict, Integer probability,
                       BreakupType myBreakupType, PartnerType partnerType, String reason,
                       Initiator whoEnded, ContactStatus contactStatus, BreakupReason breakupReason,
                       boolean partnerNewPerson, int relationshipMonths, boolean pastReunionFailed,
                       int daysSinceBreakup) {
        this.storyId = storyId;
        this.verdict = verdict;
        this.probability = probability;
        this.myBreakupType = myBreakupType;
        this.partnerType = partnerType;
        this.reason = reason;
        this.whoEnded = whoEnded;
        this.contactStatus = contactStatus;
        this.breakupReason = breakupReason;
        this.partnerNewPerson = partnerNewPerson;
        this.relationshipMonths = relationshipMonths;
        this.pastReunionFailed = pastReunionFailed;
        this.daysSinceBreakup = daysSinceBreakup;
    }
}
