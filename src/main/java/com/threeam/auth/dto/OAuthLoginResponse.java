package com.threeam.auth.dto;

import lombok.Getter;

// 소셜 로그인 응답. 보통은 토큰이지만, 게스트가 이미 가입된 소셜 계정으로 로그인하려는 경우엔
// 토큰 대신 전환 확인 티켓만 내려간다 — 게스트 사연을 잃는 전환이라 프론트가 경고를 거쳐
// confirm-switch로 확정해야 한다(인가 코드는 1회용이라 경고 후 재인가를 시킬 수 없다).
@Getter
public class OAuthLoginResponse {

    private final String grantType;
    private final String accessToken;
    private final String refreshToken;
    private final String switchTicket;

    private OAuthLoginResponse(TokenResponse tokens, String switchTicket) {
        this.grantType = tokens == null ? null : tokens.getGrantType();
        this.accessToken = tokens == null ? null : tokens.getAccessToken();
        this.refreshToken = tokens == null ? null : tokens.getRefreshToken();
        this.switchTicket = switchTicket;
    }

    public static OAuthLoginResponse loggedIn(TokenResponse tokens) {
        return new OAuthLoginResponse(tokens, null);
    }

    public static OAuthLoginResponse switchRequired(String switchTicket) {
        return new OAuthLoginResponse(null, switchTicket);
    }
}
