package com.threeam.match.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

// 사례 매칭에 쓰는 "지금 이 사연의 상황" 한 장. 진단 LLM이 대화에서 함께 뽑아 준다.
// 확률(assessments)과 달리 사연당 한 행만 두고 덮어쓴다 — 매칭이 보는 건 언제나 최신 상황이고,
// 과거 프로필은 추이로서의 값어치가 없다(확률은 변화 자체가 상품이라 쌓지만 여기는 아니다).
// 전 필드 null 허용: 대화 초반엔 안 드러난 것이 많고, 없는 걸 지어내는 쪽이 더 나쁘다.
@Entity
@Table(name = "story_match_profile")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoryMatchProfile {

    // 사연당 하나뿐이라 storyId가 그대로 PK다(대리키를 두면 중복 행이 생길 여지만 남는다).
    @Id
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

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

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

    // 새 진단이 뽑아온 값으로 덮어쓴다. null은 "이번엔 안 드러남"이지 "없어졌다"가 아니라서
    // 기존 값을 지우지 않는다 — 한 번 밝혀진 이별 사유가 다음 진단에서 사라지면 매칭이 끊긴다.
    public void merge(StoryMatchProfile fresh) {
        this.reason = fresh.reason != null ? fresh.reason : this.reason;
        this.subReasons = fresh.subReasons != null ? fresh.subReasons : this.subReasons;
        this.dumper = fresh.dumper != null ? fresh.dumper : this.dumper;
        this.fault = fresh.fault != null ? fresh.fault : this.fault;
        this.contactState = fresh.contactState != null ? fresh.contactState : this.contactState;
        this.monthsSinceBreakup = fresh.monthsSinceBreakup != null
                ? fresh.monthsSinceBreakup : this.monthsSinceBreakup;
        this.datingMonths = fresh.datingMonths != null ? fresh.datingMonths : this.datingMonths;
        this.ageGroup = fresh.ageGroup != null ? fresh.ageGroup : this.ageGroup;
        this.gender = fresh.gender != null ? fresh.gender : this.gender;
        this.repeatBreakup = fresh.repeatBreakup != null ? fresh.repeatBreakup : this.repeatBreakup;
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
