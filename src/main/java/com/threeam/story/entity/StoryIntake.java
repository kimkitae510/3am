package com.threeam.story.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 첫 대화를 시작할 때 한 번 받는 기본 정보. 나이, 기간처럼 답이 하나로 정해지는 사실을
// 산문으로 쓰게 하면 유저는 손이 아프고, 빠뜨리면 상담자가 첫 질문 세 개를 나이와 기간을
// 되묻는 데 써버려 정작 파고들 걸 못 묻는다. 그 질문 예산을 아끼려고 만든 층이다.
// 사연 단위다(유저 단위가 아니다) — 상대가 다르면 기간도 나이도 다 다르다.
//
// 시점이 박혀 있는 스냅샷이다. 연락 상태와 상대 행동은 시간이 지나면 바뀌는데 이 행은 안 바뀐다.
// 그래서 이후 변화는 StoryFact 원장이 잡고, 충돌하면 원장이 이긴다.
@Entity
@Table(name = "story_intake")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoryIntake {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 사연당 한 행(UNIQUE). 분석마다 쌓이는 StoryMatchProfile과 달리 이건 첫 시점의 한 장이다.
    @Column(nullable = false, unique = true)
    private Long storyId;

    // 상담자가 유저를 부르는 이름. 폼에서 유일한 필수 칸이다 — 이게 없으면 상담자가 대조
    // 문장에서 주어를 통째로 빠뜨린다("여자친구는 A였던 반면, 입장에서는 B"). 실명일 필요는
    // 없다(가명, 별명 가능). 신원 확인이 아니라 부르려고 받는 값이라 수집 최소화와 안 부딪힌다.
    @Column(nullable = false, length = 8)
    private String callName;

    private Integer userAge;

    private Integer partnerAge;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 10)
    private IntakeGender userGender;

    private Integer datingMonths;

    // 개월이 아니라 일 단위로 받는다. 폼의 "1주 안"은 개월로 접으면 0이라 사라지는데,
    // 이별 3일째와 25일째는 상대 행동의 의미가 전혀 다르다. 프로필의 개월은 여기서 나눠 만든다.
    private Integer daysSinceBreakup;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 20)
    private BreakupInitiator initiator;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 30)
    private ContactMode contactMode;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 20)
    private PriorReunion priorReunion;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 20)
    private PartnerNewRelation partnerHasNew;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 30)
    private PreBreakupChange preBreakupChange;

    // 다중 선택은 이름을 쉼표로 이어 한 칸에 둔다. 쿼리로 찾을 일이 없어 테이블을 쪼갤 이유가 없다.
    @Column(length = 150)
    private String partnerActions;

    @Column(length = 150)
    private String contactPoints;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private StoryIntake(Long storyId, String callName, Integer userAge, Integer partnerAge,
                        IntakeGender userGender,
                        Integer datingMonths, Integer daysSinceBreakup, BreakupInitiator initiator,
                        ContactMode contactMode, PriorReunion priorReunion,
                        PartnerNewRelation partnerHasNew, PreBreakupChange preBreakupChange,
                        List<PartnerAction> partnerActions, List<ContactPoint> contactPoints) {
        this.storyId = storyId;
        apply(callName, userAge, partnerAge, userGender, datingMonths, daysSinceBreakup, initiator,
                contactMode, priorReunion, partnerHasNew, preBreakupChange, partnerActions, contactPoints);
    }

    // 유저가 폼을 다시 열어 고칠 수 있게 통째로 갈아끼운다. 부분 수정을 받지 않는 이유는
    // 건너뛴 칸(null)과 지운 칸을 구분할 수 없어서다 — 화면이 늘 전체를 보낸다.
    public void update(String callName, Integer userAge, Integer partnerAge, IntakeGender userGender,
                       Integer datingMonths, Integer daysSinceBreakup, BreakupInitiator initiator,
                       ContactMode contactMode, PriorReunion priorReunion,
                       PartnerNewRelation partnerHasNew, PreBreakupChange preBreakupChange,
                       List<PartnerAction> partnerActions, List<ContactPoint> contactPoints) {
        apply(callName, userAge, partnerAge, userGender, datingMonths, daysSinceBreakup, initiator,
                contactMode, priorReunion, partnerHasNew, preBreakupChange, partnerActions, contactPoints);
    }

    private void apply(String callName, Integer userAge, Integer partnerAge, IntakeGender userGender,
                       Integer datingMonths, Integer daysSinceBreakup, BreakupInitiator initiator,
                       ContactMode contactMode, PriorReunion priorReunion,
                       PartnerNewRelation partnerHasNew, PreBreakupChange preBreakupChange,
                       List<PartnerAction> partnerActions, List<ContactPoint> contactPoints) {
        // 앞뒤 공백은 여기서 한 번만 턴다 — 호칭은 말풍선에 그대로 박히는 값이라
        // " 지호 "가 저장되면 "지호 님은"처럼 벌어진다.
        this.callName = callName == null ? null : callName.trim();
        this.userAge = userAge;
        this.partnerAge = partnerAge;
        this.userGender = userGender;
        this.datingMonths = datingMonths;
        this.daysSinceBreakup = daysSinceBreakup;
        this.initiator = initiator;
        this.contactMode = contactMode;
        this.priorReunion = priorReunion;
        this.partnerHasNew = partnerHasNew;
        this.preBreakupChange = preBreakupChange;
        this.partnerActions = join(partnerActions);
        this.contactPoints = join(contactPoints);
    }

    public List<PartnerAction> partnerActionList() {
        return parse(partnerActions, PartnerAction.class);
    }

    public List<ContactPoint> contactPointList() {
        return parse(contactPoints, ContactPoint.class);
    }

    // 나이 숫자를 사례 데이터의 어휘("20대 후반")로 접는다. 사례가 나이대로만 라벨링돼 있어
    // 숫자끼리는 비교할 상대가 없다. 10대 미만, 60대 이상은 사례에 없어 값을 만들지 않는다.
    public static String ageGroupOf(Integer age) {
        if (age == null || age < 10 || age >= 60) {
            return null;
        }
        int decade = age / 10 * 10;
        int within = age % 10;
        String position = within <= 3 ? "초반" : within <= 6 ? "중반" : "후반";
        return decade + "대 " + position;
    }

    private static <E extends Enum<E>> List<E> parse(String raw, Class<E> type) {
        List<E> values = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        for (String token : raw.split(",")) {
            String name = token.trim();
            if (name.isEmpty()) {
                continue;
            }
            // 사전에서 사라진 옛 값이 섞여 있어도 화면 전체가 죽지 않게 한 칸만 버린다.
            try {
                E value = Enum.valueOf(type, name);
                if (!values.contains(value)) {
                    values.add(value);
                }
            } catch (IllegalArgumentException ignored) {
                // 폐기된 선택지
            }
        }
        return values;
    }

    private static String join(List<? extends Enum<?>> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        values.stream()
                .filter(value -> value != null)
                .map(Enum::name)
                .forEach(name -> {
                    if (!names.contains(name)) {
                        names.add(name);
                    }
                });
        return names.isEmpty() ? null : String.join(",", names);
    }
}
