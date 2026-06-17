package com.threeam.story.dto;

import com.threeam.story.entity.BreakupInitiator;
import com.threeam.story.entity.ContactMode;
import com.threeam.story.entity.ContactPoint;
import com.threeam.story.entity.IntakeGender;
import com.threeam.story.entity.PartnerAction;
import com.threeam.story.entity.PartnerNewRelation;
import com.threeam.story.entity.PreBreakupChange;
import com.threeam.story.entity.PriorReunion;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

// 첫 대화 전에 받는 기본 정보. 부를 이름 하나만 필수고 나머지는 전부 선택이다 —
// 다 필수로 걸면 상담이 아니라 접수창구가 된다. 이름만 예외인 것은 안 받으면 상담자가
// 유저를 가리킬 말이 없어 대조 문장에서 주어를 통째로 빠뜨리기 때문이다(엔티티 주석 참고).
// 화면은 늘 전체를 보낸다(부분 수정을 받지 않는다. 엔티티 update 주석 참고).
// 상한은 오타 방어다: 나이 세 자리, 교제 100년 같은 값이 프로필로 새어 나가면 매칭이 엉킨다.
public record StoryIntakeRequest(
        @NotBlank(message = "뭐라고 불러드릴지 알려주세요.")
        @Size(max = 8, message = "이름은 8자까지 적을 수 있습니다.")
        String callName,

        @Min(value = 10, message = "나이를 다시 확인해 주세요.")
        @Max(value = 99, message = "나이를 다시 확인해 주세요.")
        Integer userAge,

        @Min(value = 10, message = "나이를 다시 확인해 주세요.")
        @Max(value = 99, message = "나이를 다시 확인해 주세요.")
        Integer partnerAge,

        IntakeGender userGender,

        @Min(value = 0, message = "교제 기간을 다시 확인해 주세요.")
        @Max(value = 720, message = "교제 기간을 다시 확인해 주세요.")
        Integer datingMonths,

        @Min(value = 0, message = "이별 후 기간을 다시 확인해 주세요.")
        @Max(value = 21900, message = "이별 후 기간을 다시 확인해 주세요.")
        Integer daysSinceBreakup,

        BreakupInitiator initiator,
        ContactMode contactMode,
        PriorReunion priorReunion,
        PartnerNewRelation partnerHasNew,
        PreBreakupChange preBreakupChange,

        @Size(max = 6) List<PartnerAction> partnerActions,
        @Size(max = 6) List<ContactPoint> contactPoints) {
}
