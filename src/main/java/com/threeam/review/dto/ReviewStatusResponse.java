package com.threeam.review.dto;

// 최신 진단에 대한 평가 여부. 화면이 평가 블록을 띄울지 접을지를 이걸로 정한다.
// rewardAvailable: 보상은 유저당 1회라, 이미 받은 유저에겐 보상 문구를 띄우지 않게 알려준다.
public record ReviewStatusResponse(boolean reviewed, Integer score, boolean rewardAvailable) {
}
