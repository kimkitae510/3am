package com.threeam.usage;

import lombok.Getter;

// "몇 회 남았는지" 화면 표시용. 일일 무료를 폐지하면서 잔여가 이용권 하나로 합쳐졌다 —
// 예전에는 무료 잔여와 이용권 잔여를 따로 내려 화면이 "오늘 3회 + 이용권 5회"처럼
// 두 숫자를 붙여 보여줬는데, 유저가 계산해야 했고 어느 쪽이 먼저 닳는지도 설명해야 했다.
@Getter
public class UsageStatusResponse {

    private final int chatRemaining;
    private final int assessmentRemaining;
    // 게스트면 화면이 충전 대신 '계정 연결' 동선을 보여준다(진단, 결제는 게스트 차단).
    private final boolean guest;
    // 연속 실패 쿨다운의 남은 초(0이면 없음). 여기에 실어야 방을 나갔다 들어와도 잠금이 복원된다 —
    // 없으면 화면은 멀쩡해 보이는데 보내는 순간에야 거절당한다.
    private final int chatCooldownSeconds;

    public UsageStatusResponse(int chatRemaining, int assessmentRemaining, boolean guest,
                               int chatCooldownSeconds) {
        this.chatRemaining = chatRemaining;
        this.assessmentRemaining = assessmentRemaining;
        this.guest = guest;
        this.chatCooldownSeconds = chatCooldownSeconds;
    }
}
