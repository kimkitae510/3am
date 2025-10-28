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

// 한 시점의 재회 진단 결과. 사연(storyId)별로 쌓여 시간에 따른 확률 변화 히스토리가 된다.
// 감점 항목(deductions)을 통째로 남겨 "왜 이 확률?"에 조목조목 답한다.
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

    // POSSIBLE이 아닐 땐(LET_GO/DANGER) null. 숫자를 주면 그 숫자에 매달리므로 일부러 안 준다.
    @Column
    private Integer probability;

    // 유형 라벨은 LLM이 매긴다. DANGER처럼 진단을 건너뛴 경우 null일 수 있다.
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private BreakupType myBreakupType;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PartnerType partnerType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    // 점수에서 깎인 근거. 별도 테이블(assessment_deductions)에 쌓인다.
    @ElementCollection
    @CollectionTable(name = "assessment_deductions", joinColumns = @JoinColumn(name = "assessment_id"))
    @BatchSize(size = 100)
    private List<Deduction> deductions = new ArrayList<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private Assessment(Long storyId, ReunionVerdict verdict, Integer probability,
                       BreakupType myBreakupType, PartnerType partnerType, String reason,
                       @Singular List<Deduction> deductions) {
        this.storyId = storyId;
        this.verdict = verdict;
        this.probability = probability;
        this.myBreakupType = myBreakupType;
        this.partnerType = partnerType;
        this.reason = reason;
        this.deductions = deductions != null ? deductions : new ArrayList<>();
    }
}
