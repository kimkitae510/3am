package com.threeam.usage;

// LLM 비용이 나가는 작업 종류. 종류별로 한도를 따로 가진다(대화는 잦고 싸게, 분석은 드물고 비싸게).
public enum UsageKind {
    CHAT,
    ASSESSMENT,
    // 유료 사례 매칭. 무료 매칭은 LLM을 안 불러 쿼터가 없었는데, 본문을 읽고 고르는 호출이
    // 붙으면서 종류가 생겼다. 가입 선물은 없다 — 유료 분석을 풀 때 함께 지급한다.
    MATCH
}
