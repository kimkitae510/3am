package com.threeam.assessment.dto;

import com.threeam.assessment.entity.BreakupReason;
import com.threeam.assessment.entity.ContactStatus;
import com.threeam.assessment.entity.Initiator;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Getter
public class AssessmentRequest {

    @NotNull(message = "누가 이별을 통보했는지는 필수입니다.")
    private Initiator whoEnded;

    @NotNull(message = "현재 연락 상태는 필수입니다.")
    private ContactStatus contactStatus;

    @NotNull(message = "이별 사유는 필수입니다.")
    private BreakupReason breakupReason;

    private boolean partnerNewPerson;   // 상대가 새 사람을 만나는지 → 졸업 판정 트리거

    @Min(value = 0, message = "관계 기간은 0 이상이어야 합니다.")
    private int relationshipMonths;

    private boolean pastReunionFailed;  // 이미 재회 실패 이력이 있는지

    @Min(value = 0, message = "경과일은 0 이상이어야 합니다.")
    private int daysSinceBreakup;
}
