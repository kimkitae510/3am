package com.threeam.match.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 유저 사연과 견줄 참조 사례 한 건. 앱이 쓰기만 하고 유저는 못 만든다(시드로 적재).
// 분류 어휘(reason, subReasons)는 사례와 유저 프로필이 반드시 같은 사전을 써야 겹침이 잡힌다 —
// 어휘가 어긋나면 T1 점수가 통째로 0이 된다(분류체계.md).
@Entity
@Table(name = "reunion_case")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReunionCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String story;

    @Column(length = 10)
    private String gender;

    @Column(length = 20)
    private String ageGroup;

    @Column(length = 20)
    private String reason;

    // 쉼표 구분("질투의심,무심소홀"). 앞이 주(방아쇠), 뒤가 밑에 깔린 요인.
    @Column(length = 120)
    private String subReasons;

    @Column(length = 10)
    private String dumper;

    private Integer datingMonths;

    @Column(length = 20)
    private String postBehavior;

    @Column(length = 20)
    private String contactState;

    private Integer reunionMonths;

    private Integer monthsSinceBreakup;

    // 화면 배지 문구("재회 네 달째") — 경과 개월을 사람 말로 굳혀둔 것.
    @Column(length = 20)
    private String periodLabel;

    @Column(length = 30)
    private String reunionRecord;

    @Column(length = 20)
    private String outcome;

    @Column(length = 20)
    private String fault;

    // MySQL 예약어(length)를 피한 컬럼명.
    @Column(name = "story_length", length = 10)
    private String storyLength;

    @Column(length = 20)
    private String tone;

    private Boolean repeatBreakup;

    @Column(length = 30)
    private String sourceType;

    public List<String> subReasonList() {
        return SubReasons.parse(subReasons);
    }
}
