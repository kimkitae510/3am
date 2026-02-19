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

// 진단이 내려준 행동 가이드 한 항목. 숫자(확률)만으로는 "그래서 뭘 해야 하는지"가 없어서,
// 이번 진단의 신호와 유형에서 도출한 do/dont를 근거(basis)와 함께 남긴다.
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GuidanceItem {

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private GuidanceKind kind;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String advice;  // 조언 본문(자율성 지지 톤)

    @Column(length = 200)
    private String basis;   // 어떤 신호/유형에서 나온 조언인지 한 줄. 없으면 null

    private GuidanceItem(GuidanceKind kind, String advice, String basis) {
        this.kind = kind;
        this.advice = advice;
        this.basis = basis;
    }

    public static GuidanceItem of(GuidanceKind kind, String advice, String basis) {
        return new GuidanceItem(kind, advice, basis);
    }
}
