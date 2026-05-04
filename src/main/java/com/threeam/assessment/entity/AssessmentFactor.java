package com.threeam.assessment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 진단 요인 한 슬롯의 판정 결과. 확률 재계산(제안 번복)의 재료라 판정을 통째로 남긴다.
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssessmentFactor {

    // name은 MySQL에서 무난하지만 의미를 살려 factor_name으로 매핑한다(signal_name과 결 맞춤).
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "factor_name", nullable = false, length = 20)
    private FactorName name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 15)
    private FactorLevel level;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String evidence;   // 관찰된 사실. 근거가 없으면 "근거 없음"

    @Column(length = 300)
    private String rationale;  // 이 판정이 왜 확률을 움직이는지

    // 대체자 요인 전용 세분: 썸 정황(SEEING, -6)과 새 연인 정착(SETTLED, -12, 대역 하한 관통)을
    // 가른다. 다른 요인에선 null.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 10)
    private ReplacementStage stage;

    private AssessmentFactor(FactorName name, FactorLevel level, String evidence,
                             String rationale, ReplacementStage stage) {
        this.name = name;
        this.level = level;
        this.evidence = evidence;
        this.rationale = rationale;
        this.stage = stage;
    }

    public static AssessmentFactor of(FactorName name, FactorLevel level, String evidence,
                                      String rationale, ReplacementStage stage) {
        return new AssessmentFactor(name, level, evidence, rationale,
                name == FactorName.REPLACEMENT ? stage : null);
    }
}
