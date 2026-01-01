package com.threeam.auth.oauth;

import com.threeam.user.entity.AuthProvider;

// 소셜 프로필의 공통 최소 형태. email은 동의 항목이라 null 가능(특히 카카오 기본 앱).
public record OAuthProfile(AuthProvider provider, String providerId, String nickname, String email) {
}
