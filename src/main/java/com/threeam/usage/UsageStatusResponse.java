package com.threeam.usage;

import lombok.Getter;

// "오늘 몇 회 남았는지" 화면 표시용. 한도도 함께 내려 "2/3" 형태로 그릴 수 있게 한다.
@Getter
public class UsageStatusResponse {

    private final int chatRemaining;
    private final int chatDailyLimit;
    private final int assessmentRemaining;
    private final int assessmentDailyLimit;

    public UsageStatusResponse(int chatRemaining, int chatDailyLimit,
                               int assessmentRemaining, int assessmentDailyLimit) {
        this.chatRemaining = chatRemaining;
        this.chatDailyLimit = chatDailyLimit;
        this.assessmentRemaining = assessmentRemaining;
        this.assessmentDailyLimit = assessmentDailyLimit;
    }
}
