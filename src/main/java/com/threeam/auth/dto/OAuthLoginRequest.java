package com.threeam.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class OAuthLoginRequest {

    @NotBlank(message = "인가 코드는 필수입니다.")
    private String code;

    // 네이버 토큰 교환에 필요. CSRF 대조 자체는 프론트가 sessionStorage 값과 비교한다.
    private String state;

    // 인가 요청에 쓴 redirect_uri와 같아야 토큰 교환이 성립한다.
    @NotBlank(message = "redirectUri는 필수입니다.")
    private String redirectUri;

    // 소셜은 첫 로그인이 곧 가입이라 동의를 같이 실어 보낸다. 신규 가입일 때만 검사하고
    // 기존 계정 로그인이면 무시한다(프론트는 동의 시트 통과 후에만 인가로 넘어간다).
    private java.util.Set<String> consents;
}
