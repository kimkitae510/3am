package com.threeam.llm;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;

// 외부 LLM 호출 실패를 도메인 예외로 감싼다. 전역 핸들러에서 502로 응답한다.
public class LlmException extends BusinessException {

    public LlmException() {
        super(ErrorCode.LLM_GENERATION_FAILED);
    }
}
