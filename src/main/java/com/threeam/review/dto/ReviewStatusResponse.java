package com.threeam.review.dto;

// 최신 분석에 대한 평가 상태. 점수와 후기는 언제든 고칠 수 있어 화면이 현재 값을 미리 채운다.
// rewardAvailable: 보상(후기 완성 시 1회)은 유저당 한 번이라, 이미 받은 유저에겐 보상 문구를 접는다.
public record ReviewStatusResponse(boolean reviewed, Integer score, String comment,
                                   boolean rewardAvailable) {
}
