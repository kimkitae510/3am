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
}
