package com.threeam.llm;

// LLM HTTP 오류의 상태코드를 실어 나른다 — 실패 사유 지표(quota/overloaded/timeout 구분)가
// 상태코드를 알아야 해서. 처리 흐름은 부모(LlmException)와 동일하게 탄다.
class LlmHttpStatusException extends LlmException {

    final int status;

    LlmHttpStatusException(int status) {
        this.status = status;
    }
}
