package com.threeam.auth.oauth;

import com.threeam.user.entity.AuthProvider;

public interface OAuthClient {

    // 인가 코드를 액세스 토큰으로 교환하고 프로필을 조회한다.
    // redirectUri는 인가 요청에 쓴 값과 같아야 해서 프론트가 그대로 넘긴다
    // (등록된 URI인지는 카카오/네이버가 검증하므로 위조 여지가 없다).
    OAuthProfile fetchProfile(AuthProvider provider, String code, String state, String redirectUri);
}
