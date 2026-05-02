package com.threeam.match.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

// 사례 매칭에 쓰는 "이 사연의 상황" 스냅샷. 진단 LLM이 대화에서 함께 뽑아 준다.
// 진단마다 한 행씩 새로 쌓는다(assessments와 같은 문법) — 진단은 하루 1회 쿼터라 양이 안 늘고,
// 덮어쓰면 과거 진단 시점에 상황이 어땠는지를 잃는다. 매칭은 최신 행만 읽는다.
// 전 필드 null 허용: 대화 초반엔 안 드러난 것이 많고, 없는 걸 지어내는 쪽이 더 나쁘다.
@Entity
@Table(name = "story_match_profile")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoryMatchProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long storyId;

    @Column(length = 20)
    private String reason;

    @Column(length = 120)
    private String subReasons;

    @Column(length = 10)
    private String dumper;

    @Column(length = 20)
    private String fault;

    @Column(length = 20)
    private String contactState;

    private Integer monthsSinceBreakup;

    private Integer datingMonths;

    @Column(length = 20)
    private String ageGroup;

    @Column(length = 10)
    private String gender;

    private Boolean repeatBreakup;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private StoryMatchProfile(Long storyId, String reason, String subReasons, String dumper,
                              String fault, String contactState, Integer monthsSinceBreakup,
                              Integer datingMonths, String ageGroup, String gender,
                              Boolean repeatBreakup) {
        this.storyId = storyId;
        this.reason = reason;
        this.subReasons = subReasons;
        this.dumper = dumper;
        this.fault = fault;
        this.contactState = contactState;
        this.monthsSinceBreakup = monthsSinceBreakup;
        this.datingMonths = datingMonths;
        this.ageGroup = ageGroup;
        this.gender = gender;
        this.repeatBreakup = repeatBreakup;
    }

    // 이번 진단이 못 뽑은 항목을 직전 스냅샷에서 이어받는다. null은 "이번엔 안 드러남"이지
    // "없어졌다"가 아니라서 — 한 번 밝혀진 이별 사유가 다음 진단에서 사라지면 매칭이 끊긴다.
    public void backfillFrom(StoryMatchProfile previous) {
        this.reason = this.reason != null ? this.reason : previous.reason;
        this.subReasons = this.subReasons != null ? this.subReasons : previous.subReasons;
        this.dumper = this.dumper != null ? this.dumper : previous.dumper;
        this.fault = this.fault != null ? this.fault : previous.fault;
        this.contactState = this.contactState != null ? this.contactState : previous.contactState;
        this.monthsSinceBreakup = this.monthsSinceBreakup != null
                ? this.monthsSinceBreakup : previous.monthsSinceBreakup;
        this.datingMonths = this.datingMonths != null ? this.datingMonths : previous.datingMonths;
        this.ageGroup = this.ageGroup != null ? this.ageGroup : previous.ageGroup;
        this.gender = this.gender != null ? this.gender : previous.gender;
        this.repeatBreakup = this.repeatBreakup != null ? this.repeatBreakup : previous.repeatBreakup;
    }

    // 매칭을 시작할 최소 조건. 이별 사유 축이 하나도 없으면 유사도가 나이, 기간 같은
    // 부수 항목으로만 계산돼 "비슷하지 않은데 비슷하다고 우기는" 결과가 나온다.
    public boolean matchable() {
        return reason != null || (subReasons != null && !subReasons.isBlank());
    }

    public List<String> subReasonList() {
        return SubReasons.parse(subReasons);
    }
}
