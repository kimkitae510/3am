package com.threeam.auth.oauth;

import com.threeam.user.entity.AuthProvider;

// 소셜 프로필의 공통 최소 형태. email은 동의 항목이라 null 가능(특히 카카오 기본 앱).
// 닉네임은 서비스에서 쓰지 않아 받지 않는다(수집 최소화).
public record OAuthProfile(AuthProvider provider, String providerId, String email) {
}
