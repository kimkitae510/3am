package com.threeam.auth.oauth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "oauth")
public class OAuthProperties {

    private String provider = "mock";
    private Registration kakao = new Registration();
    private Registration naver = new Registration();
    private int timeoutSeconds = 10;

    @Getter
    @Setter
    public static class Registration {
        private String clientId = "";
        private String clientSecret = "";
    }
}
