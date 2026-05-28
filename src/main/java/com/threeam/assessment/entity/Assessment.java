package com.threeam.assessment.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Singular;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 한 시점의 재회 진단 결과. 사연(storyId)별로 쌓여 시간에 따른 확률 변화 히스토리가 된다.
// v2: 자유 감점(deductions) 대신 유형(1층) + 고정 요인 판정(2층)을 남긴다 —
// 확률은 TypeBandScorer가 이 둘로 계산하므로, 제안 번복(retract-offer) 때 재진단 없이 재현된다.
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

    // Hibernate 6.x가 STRING enum을 MySQL 네이티브 ENUM으로 매핑하는 걸 막고 varchar로 고정.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ReunionVerdict verdict;

    // 잠금 판정(DATING, REUNITED, NOT_ADVISABLE)일 땐 null.
    @Column
    private Integer probability;

    // 이별 유형(1층 — 대역을 정한다). POSSIBLE에만 있고 잠금 판정은 null.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 20)
    private BreakupType breakupType;

    // 경계 유형 — 두 유형 사이에서 결정적 근거가 없을 때만 채워진다. 대역 중간을 잡는 재료라
    // 재계산(제안 번복) 때 필요해서 저장한다. 화면에는 배지로 내보내지 않는다(유형이 둘로 보이면
    // "왜 두 개냐"가 생긴다) — 그 사정은 typeEvidence 문장이 말한다.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 20)
    private BreakupType breakupTypeSecondary;

    // 유형 판정 근거 한 줄 — 화면의 유형 배지가 "왜 이 유형?"에 답하는 재료.
    @Column(length = 300)
    private String typeEvidence;

    // 점프 규칙(유형 대역을 통째로 교체하는 판) — 재계산 재료라 판정 자체를 저장한다.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 30)
    private JumpRule jumpRule;

    // 유지 전망 — 성사 확률과 별개 축.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 10)
    private RelapseRisk relapseRisk;

    @Column(length = 300)
    private String relapseReason;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    // 요인 판정(2층). 별도 테이블(assessment_factors)에 쌓인다.
    @ElementCollection
    @CollectionTable(name = "assessment_factors", joinColumns = @JoinColumn(name = "assessment_id"))
    @BatchSize(size = 100)
    private List<AssessmentFactor> factors = new ArrayList<>();

    // 관찰 포인트 — "이게 확인되면 판이 바뀐다". 별도 테이블(assessment_watch).
    @ElementCollection
    @CollectionTable(name = "assessment_watch", joinColumns = @JoinColumn(name = "assessment_id"))
    @BatchSize(size = 100)
    private List<WatchPoint> watchPoints = new ArrayList<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private Assessment(Long storyId, ReunionVerdict verdict, Integer probability,
                       BreakupType breakupType, BreakupType breakupTypeSecondary,
                       String typeEvidence, JumpRule jumpRule,
                       RelapseRisk relapseRisk, String relapseReason, String reason,
                       @Singular List<AssessmentFactor> factors,
                       @Singular List<WatchPoint> watchPoints) {
        this.storyId = storyId;
        this.verdict = verdict;
        this.probability = probability;
        this.breakupType = breakupType;
        this.breakupTypeSecondary = breakupTypeSecondary;
        this.typeEvidence = typeEvidence;
        this.jumpRule = jumpRule;
        this.relapseRisk = relapseRisk;
        this.relapseReason = relapseReason;
        this.reason = reason;
        this.factors = factors != null ? new ArrayList<>(factors) : new ArrayList<>();
        this.watchPoints = watchPoints != null ? new ArrayList<>(watchPoints) : new ArrayList<>();
    }

    // 상대 제안 확정(100)을 유저가 번복할 때 — 저장된 유형과 요인의 재계산 값으로 되돌린다(원장 정정과 세트).
    public void retractOffer(int recalculatedProbability) {
        this.probability = recalculatedProbability;
    }
}
