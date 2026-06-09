package com.threeam.review.dto;

// 최신 진단에 대한 평가 여부. 화면이 평가 블록을 띄울지 접을지를 이걸로 정한다.
public record ReviewStatusResponse(boolean reviewed, Integer score) {
}
