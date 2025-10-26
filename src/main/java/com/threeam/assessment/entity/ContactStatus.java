package com.threeam.assessment.entity;

// 이별 후 현재 연락 상태.
public enum ContactStatus {
    PARTNER_CONTACTED,  // 상대가 먼저 연락 옴
    NONE,               // 서로 연락 없음
    I_CLING,            // 내가 계속 매달림
    IGNORED,            // 읽씹/무응답
    BLOCKED             // 차단당함
}
