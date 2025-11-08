package com.threeam.usage;

// LLM 비용이 나가는 작업 종류. 종류별로 일일 한도를 따로 가진다(대화는 잦고 싸게, 진단은 드물고 비싸게).
public enum UsageKind {
    CHAT,
    ASSESSMENT
}
