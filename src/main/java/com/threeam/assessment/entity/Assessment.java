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

    // Hibernate 6.x가 STRING enum을 MySQL 네이티브 ENUM으로 매핑하는 걸 막고 varchar로 고정.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ReunionVerdict verdict;

    // 졸업 판정(LET_GO)일 땐 null. 숫자를 주면 그 숫자에 매달리므로 일부러 안 준다.
    @Column
    private Integer probability;

    // 상대 애착유형만 판정한다(유저 유형은 폐기 — 유저가 궁금한 건 상대다). 행동 근거 부족이면 null.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 20)
    private AttachmentStyle partnerAttachment;

    // 유형 판정 확신도. 유형이 null이면 함께 null.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 20)
    private AttachmentConfidence attachmentConfidence;

    // 유형 판정의 근거가 된 행동 패턴 목록(한 줄 근거의 후신 — 감점처럼 조목조목 보여준다).
    @ElementCollection
    @CollectionTable(name = "assessment_attachment_signals", joinColumns = @JoinColumn(name = "assessment_id"))
    @BatchSize(size = 100)
    private List<AttachmentSignal> attachmentSignals = new ArrayList<>();

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
                       AttachmentStyle partnerAttachment, AttachmentConfidence attachmentConfidence,
                       List<AttachmentSignal> attachmentSignals,
                       String reason, @Singular List<Deduction> deductions) {
        this.storyId = storyId;
        this.verdict = verdict;
        this.probability = probability;
        this.partnerAttachment = partnerAttachment;
        this.attachmentConfidence = attachmentConfidence;
        this.attachmentSignals = attachmentSignals != null ? attachmentSignals : new ArrayList<>();
        this.reason = reason;
        this.deductions = deductions != null ? deductions : new ArrayList<>();
    }

    // 상대 제안 확정(100)을 유저가 번복할 때 — 저장된 신호의 합산 값으로 되돌린다(원장 정정과 세트).
    public void retractOffer(int recalculatedProbability) {
        this.probability = recalculatedProbability;
    }
}
