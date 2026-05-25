package com.threeam.global.exception.custom;

import com.threeam.global.exception.ErrorCode;
import lombok.Getter;

// 쿨다운으로 거절할 때 남은 시간까지 실어 보내는 예외. 화면이 "잠시 후"가 아니라 실제
// 카운트다운을 보여주려면 초가 필요한데, 메시지 문자열에 적으면 프론트가 그걸 파싱해야 한다.
@Getter
public class RetryAfterException extends BusinessException {

    private final int retryAfterSeconds;

    public RetryAfterException(ErrorCode errorCode, int retryAfterSeconds) {
        super(errorCode);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
